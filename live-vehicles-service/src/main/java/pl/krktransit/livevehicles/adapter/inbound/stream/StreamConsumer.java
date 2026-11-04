package pl.krktransit.livevehicles.adapter.inbound.stream;

import com.google.protobuf.InvalidProtocolBufferException;
import io.lettuce.core.RedisException;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import pl.krktransit.livevehicles.application.ObservationFailure;
import pl.krktransit.livevehicles.application.ObservationSource;
import pl.krktransit.livevehicles.domain.LiveObservation;
import pl.krktransit.livevehicles.domain.SourceId;
import pl.krktransit.livevehicles.domain.TripForecast;
import pl.krktransit.livevehicles.domain.VehiclePosition;

public final class StreamConsumer implements ObservationSource, AutoCloseable {
    private static final System.Logger LOG = System.getLogger(StreamConsumer.class.getName());
    private final Supplier<StatefulRedisConnection<String, byte[]>> connections;
    private final Map<String, SourceId> streams = new LinkedHashMap<>();
    private final ObservationWireMapper mapper = new ObservationWireMapper();
    private final String prefix;
    private final int count;
    private final Duration block;
    private StatefulRedisConnection<String, byte[]> connection;
    private long malformed;

    public StreamConsumer(Supplier<StatefulRedisConnection<String, byte[]>> connections, String prefix,
                          Set<SourceId> sources, int count, Duration block) {
        this.connections = connections;
        this.prefix = prefix;
        this.count = count;
        this.block = block;
        sources.stream().sorted(java.util.Comparator.comparing(SourceId::toString))
                .forEach(source -> streams.put(prefix + ":" + source, source));
    }

    @Override
    public Map<SourceId, String> recentCursors(Set<SourceId> sources, Duration lookback) {
        try {
            String cursor = recentCursor(lookback);
            var cursors = new LinkedHashMap<SourceId, String>();
            sources.forEach(source -> cursors.put(source, cursor));
            return Map.copyOf(cursors);
        } catch (RedisException failure) { throw unavailable(failure); }
    }

    @Override
    public ReadBatch read(Map<SourceId, String> cursors) {
        try {
            var entries = connection().sync().xread(XReadArgs.Builder.count(count).block(block), offsets(cursors));
            return batch(cursors, entries);
        } catch (RedisException failure) { throw unavailable(failure); }
    }

    private String recentCursor(Duration lookback) {
        return Math.max(0, redisTimeMillis() - lookback.toMillis()) + "-0";
    }

    private long redisTimeMillis() {
        var time = connection().sync().time();
        long seconds = Long.parseLong(text(time.getFirst()));
        long micros = Long.parseLong(text(time.get(1)));
        return seconds * 1000 + micros / 1000;
    }

    private ReadBatch batch(Map<SourceId, String> cursors, List<StreamMessage<String, byte[]>> entries) {
        var observations = new ArrayList<LiveObservation>();
        var next = new LinkedHashMap<>(cursors);
        if (entries != null) entries.forEach(entry -> readEntry(entry, next, observations));
        return new ReadBatch(next, only(observations, VehiclePosition.class), only(observations, TripForecast.class));
    }

    private <T extends LiveObservation> List<T> only(List<LiveObservation> observations, Class<T> type) {
        return observations.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void readEntry(StreamMessage<String, byte[]> entry, Map<SourceId, String> cursors, List<LiveObservation> observations) {
        var source = streams.get(entry.getStream());
        cursors.put(source, entry.getId());
        try {
            mapper.decode(source, entry.getId(), entry.getBody().get("payload")).ifPresent(observations::add);
        } catch (InvalidProtocolBufferException | IllegalArgumentException failure) {
            reportMalformed(source, entry.getId(), failure);
        }
    }

    @SuppressWarnings("unchecked")
    private XReadArgs.StreamOffset<String>[] offsets(Map<SourceId, String> cursors) {
        return cursors.entrySet().stream().map(entry -> XReadArgs.StreamOffset.from(prefix + ":" + entry.getKey(), entry.getValue()))
                .toArray(XReadArgs.StreamOffset[]::new);
    }

    private void reportMalformed(SourceId source, String id, Throwable failure) {
        long total = ++malformed;
        var level = shouldWarn(total) ? System.Logger.Level.WARNING : System.Logger.Level.DEBUG;
        LOG.log(level, "Skipping malformed live observation: source={0}, id={1}, reason={2}, total={3}",
                source, id, failure.getMessage(), total);
    }

    private boolean shouldWarn(long total) { return total % 1000 == 1; }

    private String text(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes, StandardCharsets.UTF_8);
        return value.toString();
    }

    private StatefulRedisConnection<String, byte[]> connection() {
        if (connection == null) connection = connections.get();
        return connection;
    }

    private ObservationFailure unavailable(RedisException failure) {
        close();
        return new ObservationFailure("Live observation Redis is unavailable", failure);
    }

    @Override
    public void close() {
        if (connection != null) connection.close();
        connection = null;
    }
}
