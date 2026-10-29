package pl.krktransit.stopevents.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.zaxxer.hikari.HikariDataSource;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.stopevents.application.statistics.*;
import pl.krktransit.stopevents.bootstrap.MigrationCommand;
import pl.krktransit.stopevents.domain.event.*;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.statistics.*;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult.*;
import pl.krktransit.stopevents.support.OwnedPostgres;

@Testcontainers
class JdbcStatisticsReaderIT {
    private static final String CLEAR = "DELETE FROM stop_events.events";
    private static final String LOCK = "LOCK TABLE stop_events.events IN ACCESS EXCLUSIVE MODE";
    private static final String BLOCKED = """
            SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND wait_event_type = 'Lock'
                AND query LIKE '%recorded AS NOT MATERIALIZED%'
            """;
    private static final LocalDate DATE = LocalDate.of(2026, 3, 19);
    private static final SourceId SOURCE = new SourceId("krakow", "mpk-bus");
    private static final Set<SourceId> CONFIGURED = Set.of(SOURCE, new SourceId("krakow", "mobilis-bus"),
            new SourceId("krakow", "mpk-tram"));
    @Container private static final OwnedPostgres DATABASE = new OwnedPostgres();
    private JdbcStatisticsReader reader;
    private JdbcStopEventStore writer;

    @BeforeAll
    static void migrate() { MigrationCommand.migrate(DATABASE.dataSource()); }

    @BeforeEach
    void prepare() throws SQLException {
        reader = new JdbcStatisticsReader(DATABASE.dataSource(), CONFIGURED);
        writer = new JdbcStopEventStore(DATABASE.dataSource());
        try (var connection = DATABASE.dataSource().getConnection(); var statement = connection.prepareStatement(CLEAR)) {
            statement.executeUpdate();
        }
    }

    @Test
    void trendWeightsEveryEligibleStopAndKeepsAcceptedEarlyArrivals() {
        insert("A", 2, 0); insert("A", 3, 0); insert("A", 4, 0); insert("B", 2, 600);
        insert("A", 1, 9000); insert("A", 5, 9000);
        writer.insert(List.of(event(new SourceId("warsaw", "mpk-bus"), "foreign", DATE, 2, 5, 3000, "139", "PLATE", DetectionMethod.STOPPED_AT)));
        var trend = (Trend) read(Statistic.TREND, false);
        assertThat(trend.days()).containsExactly(new Day(DATE, 150.0, 2));
        insert("early", 2, -120);
        assertThat(((Trend) read(Statistic.TREND, false)).days()).containsExactly(new Day(DATE, 96.0, 3));
    }

    @Test
    void punctualityUsesInclusiveBoundariesAndNoAdditionalMinus90Cutoff() {
        int[] delays = {-180, -120, 0, 120, 121, 360, 361};
        for (int i = 0; i < delays.length; i++) insert("T" + i, 2, delays[i]);
        assertThat(read(Statistic.PUNCTUALITY, false)).isEqualTo(new Punctuality(7, 4, 2, 1));
    }

    @Test
    void rankingsCountEligibleTripsBeforeTheTopTenLimit() {
        for (int i = 0; i < 13; i++) {
            insert("T" + i, DATE, 2, 4, 0, "PLATE", DetectionMethod.STOPPED_AT);
            insert("T" + i, DATE, 3, 4, 100 + i, "PLATE", DetectionMethod.STOPPED_AT);
        }
        insert("incomplete", 2, 0);
        var segments = (MaxDelay) read(Statistic.MAX_DELAY, false);
        assertThat(segments.tripsAnalyzed()).isEqualTo(13);
        assertThat(segments.segments()).hasSize(10);
        assertThat(segments.segments().getFirst().delayGeneratedSeconds()).isEqualTo(112);
        var routes = (StatisticsResult.RouteDelay) read(Statistic.ROUTE_DELAY, false);
        assertThat(routes.tripsAnalyzed()).isEqualTo(13);
        assertThat(routes.routes()).hasSize(10);
        assertThat(routes.routes().getFirst().startDelaySeconds()).isZero();
        assertThat(routes.routes().getFirst().endDelaySeconds()).isEqualTo(112);
    }

    @Test
    void multipleSegmentsOfOneTripStillCountAsOneTrip() {
        insert("A", 2, -120); insert("A", 3, 0); insert("A", 4, 120);
        var result = (MaxDelay) read(Statistic.MAX_DELAY, false);
        assertThat(result.tripsAnalyzed()).isEqualTo(1);
        assertThat(result.segments()).hasSize(2);
        assertThat(result.segments().getFirst().fromSequence()).isEqualTo(2);
    }

    @Test
    void refusesGapsAndVehicleChangesInSegmentRankings() {
        insert("gap", 2, 0); insert("gap", 4, 500);
        insert("swap", DATE, 2, 5, 0, "FIRST", DetectionMethod.STOPPED_AT);
        insert("swap", DATE, 3, 5, 500, "SECOND", DetectionMethod.STOPPED_AT);
        var result = (MaxDelay) read(Statistic.MAX_DELAY, false);
        assertThat(result.tripsAnalyzed()).isZero();
        assertThat(result.segments()).isEmpty();
    }

    @Test
    void routeRankingRequiresBothBoundaryStopsAndOneVehicleAcrossTheFilteredTrip() {
        insert("missing", 2, 0); insert("missing", 3, 100);
        insert("swap", DATE, 2, 5, 0, "FIRST", DetectionMethod.STOPPED_AT);
        insert("swap", DATE, 3, 5, 100, "SECOND", DetectionMethod.STOPPED_AT);
        insert("swap", DATE, 4, 5, 200, "FIRST", DetectionMethod.STOPPED_AT);
        var result = (StatisticsResult.RouteDelay) read(Statistic.ROUTE_DELAY, false);
        assertThat(result.tripsAnalyzed()).isZero();
        assertThat(result.routes()).isEmpty();
    }

    @Test
    void estimatedCutoffAndIncludeFlagApplyToBothEndsOfARanking() {
        insert("before", DATE.minusDays(1), 2, 4, 0, "P", DetectionMethod.SEQ_JUMP);
        insert("before", DATE.minusDays(1), 3, 4, 100, "P", DetectionMethod.SEQ_JUMP);
        insert("after", DATE, 2, 4, 0, "P", DetectionMethod.STOPPED_AT);
        insert("after", DATE, 3, 4, 120, "P", DetectionMethod.SEQ_JUMP);
        var query = new StatisticsQuery("139", DATE.minusDays(1), DATE, true);
        var included = (MaxDelay) reader.read(Statistic.MAX_DELAY, query, new QueryCancellation());
        assertThat(included.tripsAnalyzed()).isEqualTo(1);
        assertThat(included.segments()).singleElement().satisfies(row -> {
            assertThat(row.tripId()).endsWith(":after");
            assertThat(row.estimated()).isTrue();
        });
        assertThat(((MaxDelay) read(Statistic.MAX_DELAY, false)).segments()).isEmpty();
        var route = (StatisticsResult.RouteDelay) read(Statistic.ROUTE_DELAY, true);
        assertThat(route.tripsAnalyzed()).isEqualTo(1);
        assertThat(route.routes().getFirst().estimated()).isTrue();
    }

    @Test
    void returnsNotFoundOnlyWhenNoRecordedEventsExist() {
        for (var statistic : Statistic.values()) {
            assertThatThrownBy(() -> read(statistic, false)).isInstanceOfSatisfying(StatisticsFailure.class,
                    failure -> assertThat(failure.kind()).isEqualTo(StatisticsFailure.Kind.NOT_FOUND));
        }
        insert("only-estimated", DATE, 2, 5, 60, "P", DetectionMethod.SEQ_JUMP);
        assertThat(read(Statistic.MAX_DELAY, false)).isEqualTo(new MaxDelay(0, List.of()));
        assertThat(read(Statistic.ROUTE_DELAY, false)).isEqualTo(new StatisticsResult.RouteDelay(0, List.of()));
        assertThat(read(Statistic.PUNCTUALITY, false)).isEqualTo(new Punctuality(0, 0, 0, 0));
        assertThat(read(Statistic.TREND, false)).isEqualTo(new Trend(List.of()));
    }

    @Test
    void includesOnlyConfiguredSourcesAndGroupsByServiceDate() {
        for (var source : CONFIGURED) {
            writer.insert(List.of(event(source, "A", DATE, 2, 5, 60, "139", "P", DetectionMethod.STOPPED_AT)));
        }
        writer.insert(List.of(event(new SourceId("krakow", "unconfigured"), "A", DATE, 2, 5, 900, "139", "P", DetectionMethod.STOPPED_AT)));
        insert("night", DATE.plusDays(1), 2, 5, 300, "P", DetectionMethod.STOPPED_AT);
        var result = (Trend) reader.read(Statistic.TREND, new StatisticsQuery("139", DATE, DATE.plusDays(2), false), new QueryCancellation());
        assertThat(result.days()).containsExactly(new Day(DATE, 60.0, 3), new Day(DATE.plusDays(1), 300.0, 1));
    }

    @Test
    void refusesSourcesFromMoreThanOneCity() {
        assertThatThrownBy(() -> new JdbcStatisticsReader(DATABASE.dataSource(),
                Set.of(SOURCE, new SourceId("warsaw", "mpk-bus")))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellationStopsAnActualBlockedPostgresQueryAndReleasesItsConnection() throws Exception {
        insert("A", 2, 60);
        var cancellation = new QueryCancellation();
        try (var lock = DATABASE.dataSource().getConnection(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(LOCK)) { statement.execute(); }
            var result = callers.submit(() -> reader.read(Statistic.TREND,
                    new StatisticsQuery("139", DATE, DATE, false), cancellation));
            try {
                await().atMost(Duration.ofSeconds(5)).until(this::queryIsBlocked);
                cancellation.cancel();
                assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(StatisticsFailure.class);
            } finally { lock.rollback(); cancellation.cancel(); }
        }
        assertThat(((Trend) read(Statistic.TREND, false)).days()).containsExactly(new Day(DATE, 60.0, 1));
    }

    @Test
    void exhaustedReadPoolDoesNotConsumeTheConnectionNeededForWritingEvents() throws Exception {
        try (var reads = pool(true); var writes = pool(false); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = new JdbcStatisticsReader(reads, CONFIGURED);
            var writer = new JdbcStopEventStore(writes);
            java.util.concurrent.Future<StatisticsResult> result;
            try (var occupied = reads.getConnection()) {
                result = callers.submit(() -> reader.read(Statistic.TREND,
                        new StatisticsQuery("139", DATE, DATE, false), new QueryCancellation()));
                await().atMost(Duration.ofSeconds(5)).until(() -> reads.getHikariPoolMXBean().getThreadsAwaitingConnection() == 1);
                var event = event(SOURCE, "concurrent", DATE, 2, 5, 60, "139", "P", DetectionMethod.STOPPED_AT);
                assertThat(writer.insert(List.of(event))).containsKey(event.key());
            }
            assertThat(((Trend) result.get(5, TimeUnit.SECONDS)).days()).containsExactly(new Day(DATE, 60.0, 1));
        }
    }

    private HikariDataSource pool(boolean readOnly) {
        var pool = new HikariDataSource();
        pool.setDataSource(DATABASE.dataSource());
        pool.setMaximumPoolSize(1);
        pool.setMinimumIdle(0);
        pool.setReadOnly(readOnly);
        return pool;
    }

    private boolean queryIsBlocked() throws SQLException {
        try (var connection = DATABASE.dataSource().getConnection(); var query = connection.prepareStatement(BLOCKED);
             var rows = query.executeQuery()) {
            rows.next();
            return rows.getInt(1) > 0;
        }
    }

    private StatisticsResult read(Statistic statistic, boolean estimated) {
        return reader.read(statistic, new StatisticsQuery("139", DATE, DATE, estimated), new QueryCancellation());
    }

    private void insert(String trip, int sequence, int delay) {
        insert(trip, DATE, sequence, 5, delay, "PLATE", DetectionMethod.STOPPED_AT);
    }

    private void insert(String trip, LocalDate date, int sequence, int max, int delay, String plate, DetectionMethod method) {
        writer.insert(List.of(event(SOURCE, trip, date, sequence, max, delay, "139", plate, method)));
    }

    private StopEvent event(SourceId source, String trip, LocalDate date, int sequence, int max, int delay,
                             String line, String plate, DetectionMethod method) {
        var planned = date.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(sequence * 600L);
        return new StopEvent(source, source.scope(trip), date, sequence, source.scope("stop" + sequence), line,
                "Stop " + sequence, null, 0, "End", planned, planned.plusSeconds(delay), delay, null, plate,
                method, method != DetectionMethod.STOPPED_AT, "historical-hash", max);
    }
}
