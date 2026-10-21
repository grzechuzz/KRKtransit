package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import javax.sql.DataSource;

final class PostgresVersionPurger {
    private static final String OLDEST_EXPIRED_SQL = "SELECT v.version_key FROM gtfs_schedule.schedule_versions v "
            + "WHERE v.superseded_at <= ? AND NOT EXISTS "
            + "(SELECT 1 FROM gtfs_schedule.feeds f WHERE f.current_version_key = v.version_key) "
            + "ORDER BY v.deleting_at NULLS LAST, v.superseded_at, v.version_key LIMIT 1";
    private static final String MARK_DELETING_SQL = "UPDATE gtfs_schedule.schedule_versions SET deleting_at = ? "
            + "WHERE version_key = ? AND deleting_at IS NULL";

    private static final Duration RETENTION = Duration.ofHours(24);
    private static final String DELETE_STOP_TIMES_SQL = "DELETE FROM gtfs_schedule.stop_times "
            + "WHERE ctid IN (SELECT ctid FROM gtfs_schedule.stop_times WHERE version_key = ? LIMIT ?)";
    private static final String DELETE_SHAPE_POINTS_SQL = "DELETE FROM gtfs_schedule.shape_points "
            + "WHERE ctid IN (SELECT ctid FROM gtfs_schedule.shape_points WHERE version_key = ? LIMIT ?)";
    private static final String DELETE_TRIPS_SQL = "DELETE FROM gtfs_schedule.trips "
            + "WHERE ctid IN (SELECT ctid FROM gtfs_schedule.trips WHERE version_key = ? LIMIT ?)";
    private static final String DELETE_STOPS_SQL = "DELETE FROM gtfs_schedule.stops "
            + "WHERE ctid IN (SELECT ctid FROM gtfs_schedule.stops WHERE version_key = ? LIMIT ?)";
    private static final String DELETE_ROUTES_SQL = "DELETE FROM gtfs_schedule.routes "
            + "WHERE ctid IN (SELECT ctid FROM gtfs_schedule.routes WHERE version_key = ? LIMIT ?)";
    private static final String DELETE_METADATA_SQL = "DELETE FROM gtfs_schedule.schedule_versions WHERE version_key = ?";
    private static final List<String> DELETE_CHILD_BATCH_SQL = List.of(
            DELETE_STOP_TIMES_SQL, DELETE_SHAPE_POINTS_SQL, DELETE_TRIPS_SQL, DELETE_STOPS_SQL, DELETE_ROUTES_SQL);
    private final DataSource source;
    private final Clock clock;

    PostgresVersionPurger(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    long purge(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Cleanup batch size must be positive");
        }
        Instant cutoff = clock.instant().minus(RETENTION);
        long total = 0;
        long removed;
        do {
            PostgresFailures.requireNotInterrupted();
            removed = purgeBatch(cutoff, batchSize);
            total += removed;
        } while (removed > 0);
        return total;
    }

    private long purgeBatch(Instant cutoff, int batchSize) {
        try (var transaction = PostgresTransaction.write(source)) {
            Connection connection = transaction.connection();
            ScheduleWriteLock.acquire(connection);
            PostgresFailures.requireNotInterrupted();
            OptionalLong expired = oldestExpired(connection, cutoff);
            long removed = 0;
            if (expired.isPresent()) {
                markDeleting(connection, expired.getAsLong());
                removed = deleteOwnedBatch(connection, expired.getAsLong(), batchSize);
            }
            transaction.commit();
            return removed;
        } catch (SQLException failure) {
            throw PostgresFailures.translate("purge expired schedule rows", failure);
        }
    }

    private OptionalLong oldestExpired(Connection connection, Instant cutoff) throws SQLException {
        try (var statement = connection.prepareStatement(OLDEST_EXPIRED_SQL)) {
            statement.setTimestamp(1, Timestamp.from(cutoff));
            try (var result = statement.executeQuery()) {
                return result.next() ? OptionalLong.of(result.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    private void markDeleting(Connection connection, long versionKey) throws SQLException {
        try (var statement = connection.prepareStatement(MARK_DELETING_SQL)) {
            statement.setTimestamp(1, Timestamp.from(clock.instant()));
            statement.setLong(2, versionKey);
            statement.executeUpdate();
        }
    }

    private long deleteOwnedBatch(Connection connection, long versionKey, int batchSize) throws SQLException {
        for (String sql : DELETE_CHILD_BATCH_SQL) {
            long removed = deleteChildBatch(connection, sql, versionKey, batchSize);
            if (removed > 0) {
                return removed;
            }
        }
        return deleteMetadata(connection, versionKey);
    }

    private long deleteChildBatch(Connection connection, String sql, long versionKey, int batchSize)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, versionKey);
            statement.setInt(2, batchSize);
            return statement.executeUpdate();
        }
    }

    private long deleteMetadata(Connection connection, long versionKey) throws SQLException {
        try (var statement = connection.prepareStatement(DELETE_METADATA_SQL)) {
            statement.setLong(1, versionKey);
            return statement.executeUpdate();
        }
    }
}
