package pl.krktransit.stopevents.adapter.outbound.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import pl.krktransit.stopevents.application.statistics.StatisticsCache;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public final class RedisStatisticsCache implements StatisticsCache, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(RedisStatisticsCache.class.getName());
    private static final long RECONNECT_DELAY_NANOS = Duration.ofSeconds(1).toNanos();
    private static final String GET = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local expiry = redis.call('ZSCORE', KEYS[2], ARGV[1])
            if not expiry or tonumber(expiry) <= now then
                redis.call('HDEL', KEYS[1], ARGV[1])
                redis.call('ZREM', KEYS[2], ARGV[1])
                return false
            end
            return redis.call('HGET', KEYS[1], ARGV[1])
            """;
    private static final String PUT = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now)
            for _, key in ipairs(expired) do redis.call('HDEL', KEYS[1], key) end
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            local ttl = tonumber(ARGV[3])
            redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
            redis.call('ZADD', KEYS[2], now + ttl, ARGV[1])
            local excess = redis.call('ZCARD', KEYS[2]) - tonumber(ARGV[4])
            if excess > 0 then
                local victims = redis.call('ZRANGE', KEYS[2], 0, excess - 1)
                for _, key in ipairs(victims) do
                    redis.call('HDEL', KEYS[1], key)
                    redis.call('ZREM', KEYS[2], key)
                end
            end
            local lifetime = math.max(ttl, redis.call('PTTL', KEYS[1]), redis.call('PTTL', KEYS[2]))
            redis.call('PEXPIRE', KEYS[1], lifetime)
            redis.call('PEXPIRE', KEYS[2], lifetime)
            return 1
            """;
    private final Supplier<StatefulRedisConnection<String, byte[]>> connections;
    private final String[] keys;
    private final int maximumEntries;
    private final int maximumEntryBytes;
    private final Duration timeout;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES).build();
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final AtomicBoolean rejected = new AtomicBoolean();
    private StatefulRedisConnection<String, byte[]> connection;
    private long reconnectAfter;

    public RedisStatisticsCache(Supplier<StatefulRedisConnection<String, byte[]>> connections, String prefix,
                                 int maximumEntries, int maximumEntryBytes, Duration timeout) {
        this.connections = connections;
        keys = new String[]{prefix + ":{results}:values", prefix + ":{results}:expiry"};
        this.maximumEntries = maximumEntries;
        this.maximumEntryBytes = maximumEntryBytes;
        this.timeout = timeout;
    }

    @Override
    public Optional<StatisticsResult> get(Statistic statistic, StatisticsQuery query) {
        var active = connection();
        if (active == null) return Optional.empty();
        try {
            byte[] payload = active.sync().eval(GET, ScriptOutputType.VALUE, keys, key(statistic, query));
            recovered();
            return decode(statistic, payload);
        } catch (RedisException failure) {
            failed(active, failure);
            return Optional.empty();
        }
    }

    @Override
    public void put(Statistic statistic, StatisticsQuery query, StatisticsResult result) {
        byte[] payload = json.writeValueAsBytes(result);
        if (payload.length > maximumEntryBytes) return;
        var active = connection();
        if (active == null) return;
        try {
            active.sync().eval(PUT, ScriptOutputType.INTEGER, keys, key(statistic, query), payload,
                    bytes(query.cacheTtl().toMillis()), bytes(maximumEntries));
            recovered();
        } catch (RedisException failure) { failed(active, failure); }
    }

    private Optional<StatisticsResult> decode(Statistic statistic, byte[] payload) {
        if (!usable(payload)) return Optional.empty();
        try { return Optional.ofNullable(json.readValue(payload, resultType(statistic))); }
        catch (JacksonException failure) {
            LOG.log(System.Logger.Level.WARNING, "Ignoring invalid statistics cache payload", failure);
            return Optional.empty();
        }
    }

    private static Class<? extends StatisticsResult> resultType(Statistic statistic) {
        return switch (statistic) {
            case DELAY_MAP -> StatisticsResult.DelayMap.class;
            case MAX_DELAY -> StatisticsResult.MaxDelay.class;
            case ROUTE_DELAY -> StatisticsResult.RouteDelay.class;
            case PUNCTUALITY -> StatisticsResult.Punctuality.class;
            case TREND -> StatisticsResult.Trend.class;
        };
    }

    private boolean usable(byte[] payload) { return payload != null && payload.length <= maximumEntryBytes; }

    private byte[] key(Statistic statistic, StatisticsQuery query) {
        return (statistic + ":" + query.lineNumber() + ":" + query.startDate() + ":" + query.endDate()
                + ":" + query.includeEstimated()).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] bytes(long number) { return Long.toString(number).getBytes(StandardCharsets.UTF_8); }

    private synchronized StatefulRedisConnection<String, byte[]> connection() {
        if (connection != null) return connection;
        if (System.nanoTime() < reconnectAfter) return null;
        try {
            connection = connections.get();
            connection.setTimeout(timeout);
            return connection;
        } catch (RedisException failure) {
            failed(null, failure);
            return null;
        }
    }

    private synchronized void failed(StatefulRedisConnection<String, byte[]> failed, RedisException failure) {
        if (failure instanceof RedisCommandExecutionException command) {
            rejected(command);
            return;
        }
        if (connection == failed) {
            close();
            reconnectAfter = System.nanoTime() + RECONNECT_DELAY_NANOS;
        }
        if (unavailable.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.WARNING, "Statistics cache unavailable; using SQL", failure);
        }
    }

    private void rejected(RedisCommandExecutionException failure) {
        if (rejected.compareAndSet(false, true)) {
            LOG.log(System.Logger.Level.ERROR, "Redis rejected a statistics cache command; using SQL", failure);
        }
    }

    private void recovered() {
        if (unavailable.compareAndSet(true, false)) LOG.log(System.Logger.Level.INFO, "Statistics cache recovered");
    }

    @Override
    public synchronized void close() {
        if (connection != null) connection.close();
        connection = null;
    }
}
