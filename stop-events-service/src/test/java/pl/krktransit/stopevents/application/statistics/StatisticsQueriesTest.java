package pl.krktransit.stopevents.application.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;

class StatisticsQueriesTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T23:30:00Z"), ZoneOffset.UTC);
    private static final StatisticsQuery QUERY = query("139", LocalDate.of(2026, 10, 29));
    private static final StatisticsResult RESULT = new StatisticsResult.Trend(List.of(
            new StatisticsResult.Day(QUERY.startDate(), 42.5, 3)));

    @Test
    void concurrentColdRequestsShareOneCalculation() throws Exception {
        int clients = 20;
        var arrivals = new CountDownLatch(clients);
        var reads = new AtomicInteger();
        var cache = new MemoryCache() {
            @Override public Optional<StatisticsResult> get(Statistic kind, StatisticsQuery query) {
                var value = super.get(kind, query);
                if (Thread.currentThread().getName().startsWith("client-")) {
                    arrivals.countDown();
                    await(arrivals);
                }
                return value;
            }
        };
        StatisticsReader reader = (kind, query, cancellation) -> { reads.incrementAndGet(); return RESULT; };
        try (var queries = service(reader, cache, 4);
             var callers = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("client-", 0).factory())) {
            var results = new ArrayList<java.util.concurrent.Future<StatisticsResult>>();
            for (int i = 0; i < clients; i++) results.add(callers.submit(() -> queries.get(Statistic.TREND, QUERY, new QueryCancellation())));
            for (var result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(RESULT);
            assertThat(reads).hasValue(1);
        }
    }

    @Test
    void failuresAreNotCachedAndARepeatedRequestCanRecover() {
        var calls = new AtomicInteger();
        StatisticsReader reader = (kind, query, cancellation) -> {
            if (calls.incrementAndGet() == 1) throw new StatisticsFailure(StatisticsFailure.Kind.UNAVAILABLE, "Database down");
            return RESULT;
        };
        try (var queries = service(reader, new MemoryCache(), 1)) {
            synchronized (queries) {
                assertThatThrownBy(() -> queries.get(Statistic.TREND, QUERY, new QueryCancellation()))
                        .isInstanceOfSatisfying(StatisticsFailure.class, failure -> assertThat(failure.kind()).isEqualTo(StatisticsFailure.Kind.UNAVAILABLE));
                assertThat(queries.get(Statistic.TREND, QUERY, new QueryCancellation())).isEqualTo(RESULT);
            }
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void retiringACompletedCalculationReleasesItsPermitExactlyOnce() throws Exception {
        var threads = new CopyOnWriteArrayList<Thread>();
        var workers = Executors.newThreadPerTaskExecutor(task -> {
            var thread = Thread.ofVirtual().unstarted(task);
            threads.add(thread);
            return thread;
        });
        var calls = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        StatisticsReader reader = (kind, query, cancellation) -> {
            if (query.equals(QUERY) && calls.incrementAndGet() == 1) {
                throw new StatisticsFailure(StatisticsFailure.Kind.UNAVAILABLE, "Database down");
            }
            if (query.lineNumber().equals("179")) { entered.countDown(); await(release); }
            return RESULT;
        };
        try (var queries = new StatisticsQueries(reader, new MemoryCache(), CLOCK, workers, 1);
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            synchronized (queries) {
                assertThatThrownBy(() -> queries.get(Statistic.TREND, QUERY, new QueryCancellation()))
                        .isInstanceOf(StatisticsFailure.class);
                assertThat(queries.get(Statistic.TREND, QUERY, new QueryCancellation())).isEqualTo(RESULT);
            }
            for (var thread : List.copyOf(threads)) {
                thread.join(5000);
                assertThat(thread.isAlive()).isFalse();
            }
            var active = callers.submit(() -> queries.get(Statistic.TREND, query("179", QUERY.startDate()), new QueryCancellation()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> queries.get(Statistic.TREND, query("503", QUERY.startDate()), new QueryCancellation()))
                        .isInstanceOfSatisfying(StatisticsFailure.class, failure -> assertThat(failure.kind()).isEqualTo(StatisticsFailure.Kind.BUSY));
            } finally { release.countDown(); }
            assertThat(active.get(5, TimeUnit.SECONDS)).isEqualTo(RESULT);
        }
    }

    @Test
    void validatesFutureDatesAgainstWarsawBeforeReadingTheCache() {
        var reads = new AtomicInteger();
        try (var queries = service((kind, query, cancellation) -> { reads.incrementAndGet(); return RESULT; }, new MemoryCache(), 2)) {
            assertThat(queries.get(Statistic.TREND, query("139", LocalDate.of(2026, 10, 30)), new QueryCancellation())).isEqualTo(RESULT);
            assertThatThrownBy(() -> queries.get(Statistic.TREND, query("139", LocalDate.of(2026, 10, 31)), new QueryCancellation()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(reads).hasValue(1);
        }
    }

    @Test
    void boundsDifferentQueriesWhileAllowingCacheHits() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cache = new MemoryCache();
        var cachedQuery = query("503", QUERY.startDate());
        cache.put(Statistic.TREND, cachedQuery, RESULT);
        StatisticsReader reader = (kind, query, cancellation) -> { entered.countDown(); await(release); return RESULT; };
        try (var queries = service(reader, cache, 1); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> queries.get(Statistic.TREND, QUERY, new QueryCancellation()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(queries.get(Statistic.TREND, cachedQuery, new QueryCancellation())).isEqualTo(RESULT);
                assertThatThrownBy(() -> queries.get(Statistic.TREND, query("179", QUERY.startDate()), new QueryCancellation()))
                        .isInstanceOfSatisfying(StatisticsFailure.class, failure -> assertThat(failure.kind()).isEqualTo(StatisticsFailure.Kind.BUSY));
            } finally { release.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(RESULT);
        }
    }

    @Test
    void cancellationOfTheOnlyWaiterCancelsTheActualQuery() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        StatisticsReader reader = (kind, query, cancellation) -> {
            try (var registration = cancellation.onCancel(cancelled::countDown)) {
                entered.countDown();
                await(cancelled);
                cancellation.check();
                return RESULT;
            }
        };
        var request = new QueryCancellation();
        try (var queries = service(reader, new MemoryCache(), 2); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = callers.submit(() -> queries.get(Statistic.TREND, QUERY, request));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            request.cancel();
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StatisticsFailure.class);
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void cancellingOneWaiterDoesNotCancelTheQueryStillNeededByAnother() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sharedCancelled = new AtomicBoolean();
        var reads = new AtomicInteger();
        var secondThread = new AtomicReference<Thread>();
        StatisticsReader reader = (kind, query, cancellation) -> {
            reads.incrementAndGet();
            try (var registration = cancellation.onCancel(() -> sharedCancelled.set(true))) {
                entered.countDown();
                await(release);
                return RESULT;
            }
        };
        var firstCancellation = new QueryCancellation();
        try (var queries = service(reader, new MemoryCache(), 2); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = callers.submit(() -> queries.get(Statistic.TREND, QUERY, firstCancellation));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> {
                secondThread.set(Thread.currentThread());
                return queries.get(Statistic.TREND, QUERY, new QueryCancellation());
            });
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> secondThread.get() != null
                        && secondThread.get().getState() == Thread.State.WAITING);
                firstCancellation.cancel();
                assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StatisticsFailure.class);
                assertThat(sharedCancelled).isFalse();
            } finally { release.countDown(); }
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(RESULT);
            assertThat(reads).hasValue(1);
        }
    }

    private static StatisticsQueries service(StatisticsReader reader, StatisticsCache cache, int maximum) {
        return new StatisticsQueries(reader, cache, CLOCK, Executors.newVirtualThreadPerTaskExecutor(), maximum);
    }

    private static StatisticsQuery query(String line, LocalDate date) { return new StatisticsQuery(line, date, date, false); }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Latch not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StatisticsFailure(StatisticsFailure.Kind.CANCELLED, "Interrupted test query", interrupted);
        }
    }

    private static class MemoryCache implements StatisticsCache {
        private final Map<List<Object>, StatisticsResult> values = new ConcurrentHashMap<>();
        @Override public Optional<StatisticsResult> get(Statistic kind, StatisticsQuery query) { return Optional.ofNullable(values.get(List.of(kind, query))); }
        @Override public void put(Statistic kind, StatisticsQuery query, StatisticsResult result) { values.put(List.of(kind, query), result); }
    }
}
