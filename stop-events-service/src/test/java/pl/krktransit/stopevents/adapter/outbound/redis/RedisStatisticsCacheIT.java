package pl.krktransit.stopevents.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import pl.krktransit.stopevents.domain.statistics.*;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult.*;
import pl.krktransit.stopevents.support.OwnedRedis;

@Testcontainers
class RedisStatisticsCacheIT {
    private static final LocalDate DATE = LocalDate.of(2026, 3, 19);
    private static final StatisticsQuery QUERY = new StatisticsQuery("139", DATE, DATE, false);
    private static final Trend RESULT = new Trend(List.of(new Day(DATE, 42.5, 3)));
    private static final List<CommandType> CACHE_COMMANDS = List.of(CommandType.EVAL, CommandType.TIME,
            CommandType.HGET, CommandType.HSET, CommandType.HDEL, CommandType.ZSCORE, CommandType.ZADD,
            CommandType.ZRANGEBYSCORE, CommandType.ZREMRANGEBYSCORE, CommandType.ZCARD, CommandType.ZRANGE,
            CommandType.ZREM, CommandType.PTTL, CommandType.PEXPIRE);
    @Container private static final OwnedRedis REDIS = new OwnedRedis();
    private RedisClient client;
    private StatefulRedisConnection<String, byte[]> inspection;
    private RedisStatisticsCache cache;
    private String prefix;

    @BeforeEach
    void prepare() {
        client = RedisClient.create(REDIS.ownedUrl());
        inspection = REDIS.connect(client);
        prefix = "krktransit:stop-events:statistics:test:" + UUID.randomUUID();
        cache = new RedisStatisticsCache(() -> REDIS.connect(client), prefix, 2, 65536, Duration.ofMillis(500));
    }

    @AfterEach
    void close() {
        cache.close();
        inspection.close();
        client.shutdown();
    }

    @Test
    void roundTripsAllStatisticsIncludingDatesInstantsAndNumericAverages() {
        var time = Instant.parse("2026-03-19T12:00:00.123Z");
        Map<Statistic, StatisticsResult> results = Map.of(
                Statistic.TREND, RESULT,
                Statistic.PUNCTUALITY, new Punctuality(4, 2, 1, 1),
                Statistic.MAX_DELAY, new MaxDelay(1, List.of(new Segment("trip", DATE, "139", "PLATE", "A", "B",
                        2, 3, time, time, time, time, 12, "End", false))),
                Statistic.ROUTE_DELAY, new StatisticsResult.RouteDelay(1, List.of(new Route("trip", DATE, "139", "PLATE",
                        "A", "B", time, time, time, time, 10, 20, 10, "End", true))));
        results.forEach((kind, result) -> {
            cache.put(kind, QUERY, result);
            assertThat(cache.get(kind, QUERY)).contains(result);
        });
    }

    @Test
    void roundTripsDelayMapWithMissingBandsAndEmptySegments() {
        var result = new DelayMap(List.of(new MapSegment("a|01", "b|", List.of(
                new BandDelay(DelayBand.ALL_DAY, 1, -12.5, -12.5, -12.5),
                new BandDelay(DelayBand.WEEKEND, 1, -12.5, -12.5, -12.5)))));
        cache.put(Statistic.DELAY_MAP, QUERY, result);
        assertThat(cache.get(Statistic.DELAY_MAP, QUERY)).contains(result);
        cache.put(Statistic.DELAY_MAP, QUERY, new DelayMap(List.of()));
        assertThat(cache.get(Statistic.DELAY_MAP, QUERY)).contains(new DelayMap(List.of()));
    }

    @Test
    void boundsEntriesAndAssignsTtlByInclusiveRangeLength() {
        var twoDays = new StatisticsQuery("139", DATE, DATE.plusDays(1), false);
        var week = new StatisticsQuery("139", DATE, DATE.plusDays(6), false);
        cache.put(Statistic.TREND, QUERY, RESULT);
        long shortBefore = redisTimeMillis();
        cache.put(Statistic.TREND, twoDays, RESULT);
        long shortAfter = redisTimeMillis();
        long longBefore = redisTimeMillis();
        cache.put(Statistic.TREND, week, RESULT);
        long longAfter = redisTimeMillis();
        assertThat(cache.get(Statistic.TREND, QUERY)).isEmpty();
        assertThat(cache.get(Statistic.TREND, twoDays)).contains(RESULT);
        assertThat(cache.get(Statistic.TREND, week)).contains(RESULT);
        assertThat(inspection.sync().hlen(values())).isEqualTo(2);
        assertThat(inspection.sync().pttl(values())).isBetween(1L, 300000L);
        var scores = inspection.sync().zrangeWithScores(expiry(), 0, -1);
        assertThat(scores.getFirst().getScore()).isBetween(shortBefore + 60000.0, shortAfter + 60000.0);
        assertThat(scores.get(1).getScore()).isBetween(longBefore + 300000.0, longAfter + 300000.0);
    }

    @Test
    void expiredEntriesAreNeverServedEvenWhileOtherEntriesKeepTheHashAlive() {
        cache.put(Statistic.TREND, QUERY, RESULT);
        var field = inspection.sync().zrange(expiry(), 0, -1).getFirst();
        inspection.sync().zadd(expiry(), 0, field);
        assertThat(cache.get(Statistic.TREND, QUERY)).isEmpty();
        assertThat(inspection.sync().hlen(values())).isZero();
    }

    @Test
    void keysSeparateTheStatisticLineDatesAndEstimatedFlag() {
        cache.put(Statistic.TREND, QUERY, RESULT);
        assertThat(cache.get(Statistic.PUNCTUALITY, QUERY)).isEmpty();
        assertThat(cache.get(Statistic.TREND, new StatisticsQuery("503", DATE, DATE, false))).isEmpty();
        assertThat(cache.get(Statistic.TREND, new StatisticsQuery("139", DATE, DATE, true))).isEmpty();
        assertThat(cache.get(Statistic.TREND, new StatisticsQuery("139", DATE.minusDays(1), DATE.minusDays(1), false))).isEmpty();
    }

    @Test
    void corruptAndOversizedPayloadsAreMisses() {
        cache.put(Statistic.TREND, QUERY, RESULT);
        var field = inspection.sync().zrange(expiry(), 0, -1).getFirst();
        inspection.sync().hset(values(), new String(field, StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8));
        assertThat(cache.get(Statistic.TREND, QUERY)).isEmpty();
        cache.close();
        cache = new RedisStatisticsCache(() -> REDIS.connect(client), prefix + ":small", 2, 8, Duration.ofMillis(500));
        cache.put(Statistic.TREND, QUERY, RESULT);
        assertThat(cache.get(Statistic.TREND, QUERY)).isEmpty();
    }

    @Test
    void worksWithNamespaceRestrictedAclAndOnlyItsRequiredCommands() {
        var restricted = restrictedClient(CACHE_COMMANDS);
        cache.close();
        cache = new RedisStatisticsCache(() -> REDIS.connect(restricted), prefix, 2, 65536, Duration.ofMillis(500));
        try {
            cache.put(Statistic.TREND, QUERY, RESULT);
            assertThat(cache.get(Statistic.TREND, QUERY)).contains(RESULT);
        } finally { cache.close(); restricted.shutdown(); }
    }

    @Test
    void commandRejectionKeepsTheConnectionSoGrantedPermissionsApplyImmediately() {
        var connections = new AtomicInteger();
        var restricted = restrictedClient(List.of());
        cache.close();
        cache = new RedisStatisticsCache(() -> {
            connections.incrementAndGet();
            return REDIS.connect(restricted);
        }, prefix, 2, 65536, Duration.ofMillis(500));
        try {
            cache.put(Statistic.TREND, QUERY, RESULT);
            assertThat(cache.get(Statistic.TREND, QUERY)).isEmpty();
            var grants = new AclSetuserArgs();
            CACHE_COMMANDS.forEach(grants::addCommand);
            inspection.sync().aclSetuser("statistics-test", grants);
            cache.put(Statistic.TREND, QUERY, RESULT);
            assertThat(cache.get(Statistic.TREND, QUERY)).contains(RESULT);
            assertThat(connections).hasValue(1);
        } finally { cache.close(); restricted.shutdown(); }
    }

    private RedisClient restrictedClient(List<CommandType> commands) {
        var grants = new AclSetuserArgs().reset().on().addPassword("test-password")
                .keyPattern(prefix + ":*").noCommands().resetChannels();
        grants.addCommand(CommandType.HELLO);
        commands.forEach(grants::addCommand);
        grants.addCommand(CommandType.CLIENT, CommandKeyword.SETNAME);
        grants.addCommand(CommandType.CLIENT, CommandKeyword.SETINFO);
        inspection.sync().aclSetuser("statistics-test", grants);
        return RedisClient.create(RedisURI.builder(RedisURI.create(REDIS.ownedUrl()))
                .withAuthentication("statistics-test", "test-password").build());
    }

    private String values() { return prefix + ":{results}:values"; }
    private String expiry() { return prefix + ":{results}:expiry"; }

    private long redisTimeMillis() {
        var time = inspection.sync().time();
        return Long.parseLong(new String(time.getFirst(), StandardCharsets.UTF_8)) * 1000
                + Long.parseLong(new String(time.get(1), StandardCharsets.UTF_8)) / 1000;
    }
}
