package pl.krktransit.stopevents.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
    void delayMapAggregatesSignedGainsWithInterpolatedPercentilesAndNormalizedKeys() {
        mapPair("one", DATE, "08:00", 10, "UR Al. 29 Listopada", "01");
        mapPair("two", DATE, "08:30", 30, "ur al. 29 listopada", "01");
        mapPair("three", DATE, "09:00", -10, "UR AL. 29 LISTOPADA", "01");
        var result = (DelayMap) read(Statistic.DELAY_MAP, false);
        assertThat(result.segments()).singleElement().satisfies(segment -> {
            assertThat(segment.fromStopKey()).isEqualTo("ur al. 29 listopada|01");
            assertThat(segment.toStopKey()).isEqualTo("zażółć|");
            assertThat(segment.delays()).containsExactly(
                    new BandDelay(DelayBand.ALL_DAY, 3, 10, 10, 26),
                    new BandDelay(DelayBand.MIDDAY, 1, -10, -10, -10),
                    new BandDelay(DelayBand.MORNING, 2, 20, 20, 28));
        });
    }

    @Test
    void delayMapUsesStartStopWarsawHourAndServiceDateForAllBandBoundaries() {
        String[] times = {"06:59", "07:00", "08:59", "09:00", "14:59", "15:00", "17:59", "18:00", "23:59"};
        for (int index = 0; index < times.length; index++) mapPair("time" + index, DATE, times[index], 10, "A", null);
        mapPair("saturday", DATE.plusDays(2), "08:00", 10, "A", null);
        mapPair("sunday", DATE.plusDays(3), "16:00", 10, "A", null);
        var friday = DATE.plusDays(1);
        var nextMorning = friday.plusDays(1).atTime(8, 0).atZone(java.time.ZoneId.of("Europe/Warsaw")).toInstant();
        mapPairAt("service-friday", friday, nextMorning, 10, "A", null);
        var result = (DelayMap) reader.read(Statistic.DELAY_MAP,
                new StatisticsQuery("139", DATE, DATE.plusDays(3), false), new QueryCancellation());
        var bands = result.segments().getFirst().delays().stream().collect(java.util.stream.Collectors.toMap(BandDelay::band, BandDelay::samples));
        assertThat(bands).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(DelayBand.ALL_DAY, 12L,
                DelayBand.MORNING, 3L, DelayBand.MIDDAY, 2L, DelayBand.AFTERNOON, 2L,
                DelayBand.EVENING_NIGHT, 3L, DelayBand.WEEKEND, 2L));
    }

    @Test
    void delayMapKeepsWarsawMorningAcrossTheDaylightSavingChange() {
        var before = LocalDate.of(2026, 3, 27);
        var after = LocalDate.of(2026, 3, 30);
        mapPair("winter", before, "07:00", 10, "A", null);
        mapPair("summer", after, "07:00", 30, "A", null);
        var result = (DelayMap) reader.read(Statistic.DELAY_MAP,
                new StatisticsQuery("139", before, after, false), new QueryCancellation());
        assertThat(result.segments().getFirst().delays()).containsExactly(
                new BandDelay(DelayBand.ALL_DAY, 2, 20, 20, 28),
                new BandDelay(DelayBand.MORNING, 2, 20, 20, 28));
    }

    @Test
    void delayMapRefusesGapsVehicleChangesAndExcludedEstimatedEndpoints() {
        insert("gap", 2, 0); insert("gap", 4, 500);
        insert("swap", DATE, 2, 5, 0, "A", DetectionMethod.STOPPED_AT);
        insert("swap", DATE, 3, 5, 500, "B", DetectionMethod.STOPPED_AT);
        insert("estimated", DATE, 2, 4, 0, "P", DetectionMethod.STOPPED_AT);
        insert("estimated", DATE, 3, 4, 100, "P", DetectionMethod.SEQ_JUMP);
        insert("ends", DATE, 1, 4, 0, "P", DetectionMethod.STOPPED_AT);
        insert("ends", DATE, 4, 4, 500, "P", DetectionMethod.STOPPED_AT);
        assertThat(read(Statistic.DELAY_MAP, false)).isEqualTo(new DelayMap(List.of()));
        assertThat(((DelayMap) read(Statistic.DELAY_MAP, true)).segments()).singleElement()
                .satisfies(segment -> assertThat(segment.delays()).allSatisfy(band -> assertThat(band.samples()).isEqualTo(1)));
        insert("old", DATE.minusDays(1), 2, 4, 0, "P", DetectionMethod.SEQ_JUMP);
        insert("old", DATE.minusDays(1), 3, 4, 100, "P", DetectionMethod.SEQ_JUMP);
        assertThat(reader.read(Statistic.DELAY_MAP, new StatisticsQuery("139", DATE.minusDays(1), DATE.minusDays(1), true),
                new QueryCancellation())).isEqualTo(new DelayMap(List.of()));
    }

    private void mapPair(String trip, LocalDate date, String time, int gain, String name, String description) {
        var planned = date.atTime(java.time.LocalTime.parse(time)).atZone(java.time.ZoneId.of("Europe/Warsaw")).toInstant();
        mapPairAt(trip, date, planned, gain, name, description);
    }

    private void mapPairAt(String trip, LocalDate date, java.time.Instant planned, int gain, String name, String description) {
        writer.insert(List.of(
                new StopEvent(SOURCE, SOURCE.scope(trip), date, 2, SOURCE.scope("A"), "139", name, description, 0, "End",
                        planned, planned.plusSeconds(60), 60, null, "PLATE", DetectionMethod.STOPPED_AT, false, "hash", 4),
                new StopEvent(SOURCE, SOURCE.scope(trip), date, 3, SOURCE.scope("B"), "139", "ZAŻÓŁĆ", null, 0, "End",
                        planned.plusSeconds(600), planned.plusSeconds(660 + gain), 60 + gain,
                        null, "PLATE", DetectionMethod.STOPPED_AT, false, "hash", 4)));
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

    @ParameterizedTest
    @EnumSource(value = Statistic.class, names = {"TREND", "DELAY_MAP"})
    void cancellationStopsAnActualBlockedPostgresQueryAndReleasesItsConnection(Statistic statistic)
            throws SQLException, InterruptedException, ExecutionException, TimeoutException {
        insert("A", 2, 60);
        var cancellation = new QueryCancellation();
        try (var lock = DATABASE.dataSource().getConnection(); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement(LOCK)) { statement.execute(); }
            var result = callers.submit(() -> reader.read(statistic,
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
