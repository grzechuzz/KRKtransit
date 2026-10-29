package pl.krktransit.schedule.adapter.outbound.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import pl.krktransit.schedule.application.importing.ImportFailure;
import pl.krktransit.schedule.application.importing.PublicationOutcome;
import pl.krktransit.schedule.application.port.ScheduleWriter;
import pl.krktransit.schedule.application.querying.LookupFailure;
import pl.krktransit.schedule.bootstrap.MigrationCommand;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.Route;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;
import pl.krktransit.schedule.domain.schedule.ShapePoint;
import pl.krktransit.schedule.domain.schedule.Stop;
import pl.krktransit.schedule.domain.schedule.StopTime;
import pl.krktransit.schedule.domain.schedule.Trip;
import pl.krktransit.schedule.support.SchedulePostgresContainer;

@Testcontainers
class PostgresPublicationIT {
    private static final String VERSION_COUNT_SQL = "SELECT count(*) FROM gtfs_schedule.schedule_versions v JOIN gtfs_schedule.feeds f "
            + "ON f.feed_key = v.feed_key WHERE f.city_id = ? AND f.source_id = ?";
    private static final String CURRENT_SUPERSEDED_SQL = "SELECT v.superseded_at IS NOT NULL FROM gtfs_schedule.schedule_versions v "
            + "JOIN gtfs_schedule.feeds f ON f.current_version_key = v.version_key "
            + "WHERE f.city_id = ? AND f.source_id = ?";
    private static final String VERSION_KEY_SQL = "SELECT v.version_key FROM gtfs_schedule.schedule_versions v JOIN gtfs_schedule.feeds f "
            + "ON f.feed_key = v.feed_key WHERE f.city_id = ? AND f.source_id = ? AND v.zip_hash = ?";

    private static final String MARK_DELETING_SQL =
            "UPDATE gtfs_schedule.schedule_versions SET deleting_at = ? WHERE version_key = ?";
    private static final String DELETE_STOP_TIMES_SQL = "DELETE FROM gtfs_schedule.stop_times WHERE version_key = ?";
    private static final String STOP_TIME_COUNT_SQL = "SELECT count(*) FROM gtfs_schedule.stop_times WHERE version_key = ?";
    private static final String CLEAR_DEPARTURE_SQL =
            "UPDATE gtfs_schedule.stop_times SET departure_seconds = NULL WHERE version_key = ?";

    @Container
    private static final SchedulePostgresContainer POSTGRES = new SchedulePostgresContainer();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    private static final ScheduleHash FIRST = new ScheduleHash("a".repeat(64));
    private static final ScheduleHash SECOND = new ScheduleHash("b".repeat(64));
    private static DataSource source;

    @BeforeAll
    static void migrateOwnedDatabase() {
        source = POSTGRES.dataSource();
        MigrationCommand.migrate(source);
    }

    @Test
    void distinguishesUnknownUnpublishedAndMissingEntityLookups() {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        var reader = new PostgresScheduleReader(source, CLOCK);

        assertThatThrownBy(() -> reader.currentHash(feed)).isInstanceOfSatisfying(LookupFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.UNKNOWN_FEED));
        publication.registerFeeds(java.util.List.of(feed));
        assertThatThrownBy(() -> reader.currentHash(feed)).isInstanceOfSatisfying(LookupFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.NOT_PUBLISHED));

        publication.publish(feed, FIRST, writer -> load(writer, feed, 36_000, false));
        String sqlLikeId = feed.scope("missing' OR '1'='1' --");
        assertThatThrownBy(() -> reader.tripContext(FIRST, sqlLikeId)).isInstanceOfSatisfying(LookupFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.ENTITY_NOT_FOUND));
        assertThatThrownBy(() -> reader.shape(FIRST, sqlLikeId)).isInstanceOfSatisfying(LookupFailure.class,
                failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.ENTITY_NOT_FOUND));
    }

    @Test
    void rejectsNullDepartureWithoutChangingPublishedSchedule() throws SQLException {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        publication.publish(feed, FIRST, writer -> load(writer, feed, 0, false));
        long version = versionKey(feed, FIRST);

        try (var connection = source.getConnection(); var statement = connection.prepareStatement(CLEAR_DEPARTURE_SQL)) {
            statement.setLong(1, version);
            assertThatThrownBy(statement::executeUpdate).isInstanceOfSatisfying(SQLException.class,
                    failure -> assertThat(failure.getSQLState()).isEqualTo("23502"));
        }

        var reader = new PostgresScheduleReader(source, CLOCK);
        assertThat(reader.currentHash(feed)).isEqualTo(FIRST);
        assertThat(reader.tripContext(FIRST, feed.scope("trip")).stops()).singleElement().satisfies(stop -> {
            assertThat(stop.arrivalSeconds()).isZero();
            assertThat(stop.departureSeconds()).isEqualTo(30);
        });
    }

    @Test
    void failedCopyRollsBackVersionAndKeepsPreviouslyPublishedData() throws SQLException {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        var reader = new PostgresScheduleReader(source, CLOCK);
        publication.publish(feed, FIRST, sink -> load(sink, feed, 36_000, false));

        assertThatThrownBy(() -> publication.publish(feed, SECOND, sink -> load(sink, feed, 37_000, true)))
                .isInstanceOfSatisfying(ImportFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(ImportFailure.Kind.VALIDATION));

        assertThat(reader.currentHash(feed)).isEqualTo(FIRST);
        assertThat(reader.tripContext(FIRST, feed.scope("trip")).stops().getFirst().arrivalSeconds())
                .isEqualTo(36_000);
        assertThat(versionCount(feed)).isEqualTo(1);
        assertThat(currentSuperseded(feed)).isFalse();
    }

    @Test
    void lateRowSourceFailureRollsBackFinishedGroupsAndPartialCopy() throws SQLException {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        var reader = new PostgresScheduleReader(source, CLOCK);
        publication.publish(feed, FIRST, writer -> load(writer, feed, 36_000, false));
        var original = new ImportFailure(ImportFailure.Kind.VALIDATION, "Malformed row after a partial COPY");

        assertThatThrownBy(() -> publication.publish(feed, SECOND, writer -> {
            loadTripMetadata(writer, feed, false);
            writer.writeStopTimes(receiver -> {
                for (int sequence = 1; sequence <= 2_000; sequence++) {
                    receiver.accept(new StopTime(feed.scope("trip"), sequence, feed.scope("stop"),
                            37_000 + sequence, 37_030 + sequence));
                }
                throw original;
            });
        })).isSameAs(original);

        assertThat(reader.currentHash(feed)).isEqualTo(FIRST);
        assertThat(reader.tripContext(FIRST, feed.scope("trip")).stops()).singleElement()
                .satisfies(stop -> assertThat(stop.arrivalSeconds()).isEqualTo(36_000));
        assertThat(versionCount(feed)).isEqualTo(1);
        assertThat(currentSuperseded(feed)).isFalse();
    }

    @Test
    void readersSeeCompleteOldPublicationUntilReplacementCommits() throws Exception {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        var reader = new PostgresScheduleReader(source, CLOCK);
        publication.publish(feed, FIRST, sink -> load(sink, feed, 36_000, false));
        var copied = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var replacement = executor.submit(() -> publication.publish(feed, SECOND, sink -> {
                load(sink, feed, 37_000, false);
                copied.countDown();
                awaitRelease(release);
            }));
            try {
                assertThat(copied.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(reader.currentHash(feed)).isEqualTo(FIRST);
                var old = reader.tripContext(FIRST, feed.scope("trip"));
                assertThat(old.hash()).isEqualTo(FIRST);
                assertThat(old.stops().getFirst().arrivalSeconds()).isEqualTo(36_000);
            } finally {
                release.countDown();
            }
            assertThat(replacement.get(20, TimeUnit.SECONDS)).isEqualTo(PublicationOutcome.PUBLISHED);
        }

        assertThat(reader.currentHash(feed)).isEqualTo(SECOND);
        assertThat(reader.tripContext(SECOND, feed.scope("trip")).stops().getFirst().arrivalSeconds())
                .isEqualTo(37_000);
        assertThat(reader.tripContext(FIRST, feed.scope("trip")).stops().getFirst().arrivalSeconds())
                .isEqualTo(36_000);
    }

    @Test
    void identicalHashSkipsLoaderAndHistoricalHashReactivatesCompleteVersion() throws SQLException {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        var reader = new PostgresScheduleReader(source, CLOCK);
        publication.publish(feed, FIRST, sink -> load(sink, feed, 36_000, false));

        assertThat(publication.publish(feed, FIRST, sink -> {
            throw new AssertionError("Unchanged input must not be loaded");
        })).isEqualTo(PublicationOutcome.UNCHANGED);

        publication.publish(feed, SECOND, sink -> load(sink, feed, 37_000, false));
        assertThat(publication.publish(feed, FIRST, sink -> {
            throw new AssertionError("Complete retained input can be reused");
        })).isEqualTo(PublicationOutcome.REACTIVATED);

        assertThat(reader.currentHash(feed)).isEqualTo(FIRST);
        assertThat(versionCount(feed)).isEqualTo(2);
        assertThat(reader.shape(FIRST, feed.scope("shape")).points()).hasSize(2);
    }

    @Test
    void reimportDuringInterruptedCleanupCreatesCompleteReplacementAndCleanupResumes() throws SQLException {
        FeedId feed = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        publication.publish(feed, FIRST, sink -> load(sink, feed, 36_000, false));
        publication.publish(feed, SECOND, sink -> load(sink, feed, 37_000, false));
        long oldVersion = versionKey(feed, FIRST);
        simulateInterruptedCleanup(oldVersion);
        Clock tomorrow = Clock.offset(CLOCK, java.time.Duration.ofHours(24));
        var laterPublication = new PostgresSchedulePublication(source, tomorrow);

        assertThat(laterPublication.publish(feed, FIRST, sink -> load(sink, feed, 38_000, false)))
                .isEqualTo(PublicationOutcome.PUBLISHED);
        assertThat(versionCount(feed)).isEqualTo(3);
        assertThat(laterPublication.purgeExpired(1)).isGreaterThan(0);

        var reader = new PostgresScheduleReader(source, tomorrow);
        assertThat(versionCount(feed)).isEqualTo(2);
        assertThat(reader.currentHash(feed)).isEqualTo(FIRST);
        assertThat(reader.tripContext(FIRST, feed.scope("trip")).stops().getFirst().arrivalSeconds())
                .isEqualTo(38_000);
    }

    @Test
    void snapshotKeepsOldRowsWhileExpiredVersionIsDeletedInOtherTransactions() throws SQLException {
        FeedId feed = uniqueFeed();
        ScheduleHash expiredHash = new ScheduleHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        var publication = new PostgresSchedulePublication(source, CLOCK);
        publication.publish(feed, expiredHash, sink -> load(sink, feed, 36_000, false));
        publication.publish(feed, SECOND, sink -> load(sink, feed, 37_000, false));
        long oldVersion = versionKey(feed, expiredHash);
        Clock tomorrow = Clock.offset(CLOCK, java.time.Duration.ofHours(24));

        try (var snapshot = PostgresTransaction.read(source)) {
            assertThat(snapshot.connection().isReadOnly()).isTrue();
            assertThat(stopTimeCount(snapshot.connection(), oldVersion)).isEqualTo(1);

            new PostgresSchedulePublication(source, tomorrow).purgeExpired(1);

            assertThat(stopTimeCount(snapshot.connection(), oldVersion)).isEqualTo(1);
        }
        try (var connection = source.getConnection()) {
            assertThat(stopTimeCount(connection, oldVersion)).isZero();
        }
        assertThatThrownBy(() -> new PostgresScheduleReader(source, tomorrow)
                .tripContext(expiredHash, feed.scope("trip")))
                .isInstanceOfSatisfying(LookupFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.VERSION_UNAVAILABLE));
    }

    @Test
    void sharedZipCannotMakeExpiredOwnersVersionAvailableThroughAnotherSource() {
        FeedId replaced = uniqueFeed();
        FeedId unchanged = uniqueFeed();
        var publication = new PostgresSchedulePublication(source, CLOCK);
        publication.publish(replaced, FIRST, sink -> load(sink, replaced, 36_000, false));
        publication.publish(unchanged, FIRST, sink -> load(sink, unchanged, 35_000, false));
        publication.publish(replaced, SECOND, sink -> load(sink, replaced, 37_000, false));
        Clock tomorrow = Clock.offset(CLOCK, java.time.Duration.ofHours(24));
        var reader = new PostgresScheduleReader(source, tomorrow);

        assertUnavailableEntities(reader, FIRST, replaced);
        assertThat(reader.tripContext(FIRST, unchanged.scope("trip")).stops().getFirst().arrivalSeconds())
                .isEqualTo(35_000);
        assertThat(reader.shape(FIRST, unchanged.scope("shape")).points()).hasSize(2);

        new PostgresSchedulePublication(source, tomorrow).purgeExpired(1);

        assertUnavailableEntities(reader, FIRST, replaced);
        assertThat(reader.currentHash(unchanged)).isEqualTo(FIRST);
        assertThat(reader.tripContext(FIRST, unchanged.scope("trip")).stops().getFirst().arrivalSeconds())
                .isEqualTo(35_000);
        assertThat(reader.shape(FIRST, unchanged.scope("shape")).points()).hasSize(2);
    }

    private void assertUnavailableEntities(PostgresScheduleReader reader, ScheduleHash hash, FeedId feed) {
        assertThatThrownBy(() -> reader.tripContext(hash, feed.scope("trip")))
                .isInstanceOfSatisfying(LookupFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.VERSION_UNAVAILABLE));
        assertThatThrownBy(() -> reader.shape(hash, feed.scope("shape")))
                .isInstanceOfSatisfying(LookupFailure.class,
                        failure -> assertThat(failure.kind()).isEqualTo(LookupFailure.Kind.VERSION_UNAVAILABLE));
    }

    private static FeedId uniqueFeed() {
        return new FeedId("testcity", "pg" + UUID.randomUUID().toString().replace("-", ""));
    }

    private static void load(ScheduleWriter writer, FeedId feed, int seconds, boolean duplicateTrip) {
        loadTripMetadata(writer, feed, duplicateTrip);
        writer.writeStopTimes(receiver -> receiver.accept(
                new StopTime(feed.scope("trip"), 4, feed.scope("stop"), seconds, seconds + 30)));
        writer.writeShapePoints(receiver -> {
            receiver.accept(new ShapePoint(feed.scope("shape"), 50.0, 19.0, 1));
            receiver.accept(new ShapePoint(feed.scope("shape"), 50.1, 19.1, 1));
        });
    }

    private static void loadTripMetadata(ScheduleWriter writer, FeedId feed, boolean duplicateTrip) {
        writer.writeRoutes(receiver -> receiver.accept(new Route(feed.scope("route"), "001")));
        writer.writeStops(receiver -> receiver.accept(
                new Stop(feed.scope("stop"), "Dworzec, \"Główny\"", null, null, null, 19.9)));
        writer.writeTrips(receiver -> {
            var trip = new Trip(feed.scope("trip"), feed.scope("route"), "service", 0,
                    "Centrum", feed.scope("shape"));
            receiver.accept(trip);
            if (duplicateTrip) {
                receiver.accept(trip);
            }
        });
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release blocked publication");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ImportFailure(ImportFailure.Kind.CANCELLED, "Test publication interrupted", interrupted);
        }
    }

    private long versionCount(FeedId feed) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(VERSION_COUNT_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private boolean currentSuperseded(FeedId feed) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(CURRENT_SUPERSEDED_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private long versionKey(FeedId feed, ScheduleHash hash) throws SQLException {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(VERSION_KEY_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            statement.setString(3, hash.value());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void simulateInterruptedCleanup(long versionKey) throws SQLException {
        try (var transaction = PostgresTransaction.write(source)) {
            try (var mark = transaction.connection().prepareStatement(MARK_DELETING_SQL)) {
                mark.setTimestamp(1, java.sql.Timestamp.from(CLOCK.instant().plusSeconds(86_400)));
                mark.setLong(2, versionKey);
                mark.executeUpdate();
            }
            try (var delete = transaction.connection().prepareStatement(DELETE_STOP_TIMES_SQL)) {
                delete.setLong(1, versionKey);
                delete.executeUpdate();
            }
            transaction.commit();
        }
    }

    private long stopTimeCount(java.sql.Connection connection, long versionKey) throws SQLException {
        try (var statement = connection.prepareStatement(STOP_TIME_COUNT_SQL)) {
            statement.setLong(1, versionKey);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
