package pl.krktransit.stopevents.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisConnectionException;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.adapter.outbound.redis.RedisStatisticsCache;
import pl.krktransit.stopevents.application.statistics.QueryCancellation;
import pl.krktransit.stopevents.application.statistics.StatisticsFailure;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;

class StatisticsConfigurationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 29);

    @Test
    void distinctCalculationsWaitForReadConnectionsUpToTheirOwnLimit() throws Exception {
        var waiting = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var source = new WaitingDataSource(waiting, release);
        var properties = new StatisticsProperties(1, 3, Duration.ofSeconds(10), "",
                new StatisticsProperties.Cache("test", 10, 1024, Duration.ofMillis(100)));
        try (var queries = new StatisticsConfiguration().statisticsQueries(source, offlineCache(), CLOCK, stopEvents(), properties);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var line : List.of("1", "2", "3")) {
                callers.submit(() -> queries.get(Statistic.TREND, query(line), new QueryCancellation()));
            }
            try {
                assertThat(waiting.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> queries.get(Statistic.TREND, query("4"), new QueryCancellation()))
                        .isInstanceOfSatisfying(StatisticsFailure.class,
                                failure -> assertThat(failure.kind()).isEqualTo(StatisticsFailure.Kind.BUSY));
            } finally {
                release.countDown();
            }
        }
    }

    private static StatisticsQuery query(String line) { return new StatisticsQuery(line, DAY, DAY, false); }

    private static RedisStatisticsCache offlineCache() {
        return new RedisStatisticsCache(() -> { throw new RedisConnectionException("offline"); }, "test", 10, 1024,
                Duration.ofMillis(100));
    }

    private static StopEventsProperties stopEvents() {
        return new StopEventsProperties(false, List.of(new StopEventsProperties.Source("krakow", "mpk-bus")), "streams",
                "state", 10, Duration.ofSeconds(1), Duration.ofHours(3), List.of(Duration.ofSeconds(1)),
                Duration.ofSeconds(5), new StopEventsProperties.Redis("redis://127.0.0.1:1", "default", "", "test",
                Duration.ofSeconds(1), Duration.ofSeconds(2)),
                new StopEventsProperties.Schedule("static://127.0.0.1:1", "", Duration.ofSeconds(1), 10, Duration.ofSeconds(30)));
    }

    private static final class WaitingDataSource extends HikariDataSource {
        private final CountDownLatch waiting;
        private final CountDownLatch release;

        private WaitingDataSource(CountDownLatch waiting, CountDownLatch release) {
            this.waiting = waiting;
            this.release = release;
        }

        @Override
        public Connection getConnection() throws SQLTransientConnectionException {
            waiting.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new SQLTransientConnectionException("Read pool exhausted");
        }
    }
}
