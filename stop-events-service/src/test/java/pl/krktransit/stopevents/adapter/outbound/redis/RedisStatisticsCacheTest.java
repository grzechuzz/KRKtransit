package pl.krktransit.stopevents.adapter.outbound.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.application.statistics.*;
import pl.krktransit.stopevents.domain.statistics.*;

class RedisStatisticsCacheTest {
    @Test
    void redisOutageFallsBackToSql() {
        var connections = new AtomicInteger();
        var reads = new AtomicInteger();
        var date = LocalDate.of(2026, 3, 19);
        var query = new StatisticsQuery("139", date, date, false);
        var result = new StatisticsResult.Trend(List.of(new StatisticsResult.Day(date, 42.5, 1)));
        try (var cache = new RedisStatisticsCache(() -> {
            connections.incrementAndGet();
            throw new RedisConnectionException("Owned test cache offline");
        }, "unused", 2, 65536, Duration.ofMillis(500));
             var queries = new StatisticsQueries((kind, request, cancellation) -> { reads.incrementAndGet(); return result; },
                     cache, Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC),
                     Executors.newVirtualThreadPerTaskExecutor(), 4)) {
            assertThat(queries.get(Statistic.TREND, query, new QueryCancellation())).isEqualTo(result);
            assertThat(connections.get()).isPositive();
            assertThat(reads).hasValue(1);
        }
    }
}
