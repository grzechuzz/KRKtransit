package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

final class PostgresVersions {
    private static final String REGISTER_FEED_SQL = "INSERT INTO gtfs_schedule.feeds (city_id, source_id) VALUES (?, ?) "
            + "ON CONFLICT (city_id, source_id) DO NOTHING";
    private static final String FIND_FEED_SQL = "SELECT feed_key, current_version_key FROM gtfs_schedule.feeds "
            + "WHERE city_id = ? AND source_id = ?";
    private static final String CURRENT_HASH_SQL = "SELECT v.zip_hash FROM gtfs_schedule.feeds f "
            + "JOIN gtfs_schedule.schedule_versions v ON v.version_key = f.current_version_key "
            + "WHERE f.city_id = ? AND f.source_id = ?";
    private static final String REUSABLE_VERSION_SQL = "SELECT version_key FROM gtfs_schedule.schedule_versions "
            + "WHERE feed_key = ? AND zip_hash = ? AND deleting_at IS NULL";
    private static final String CREATE_VERSION_SQL = "INSERT INTO gtfs_schedule.schedule_versions (feed_key, zip_hash, published_at) "
            + "VALUES (?, ?, ?) RETURNING version_key";
    private static final String SUPERSEDE_VERSION_SQL = "UPDATE gtfs_schedule.schedule_versions SET superseded_at = ? WHERE version_key = ?";
    private static final String PUBLISH_VERSION_SQL = "UPDATE gtfs_schedule.schedule_versions SET superseded_at = NULL, published_at = ? "
            + "WHERE version_key = ? AND deleting_at IS NULL";

    private static final String POINT_CURRENT_VERSION_SQL =
            "UPDATE gtfs_schedule.feeds SET current_version_key = ? WHERE feed_key = ?";

    record FeedVersion(long feedKey, Long currentVersionKey) {}

    private PostgresVersions() {}

    static void register(Connection connection, FeedId feed) throws SQLException {
        try (var statement = connection.prepareStatement(REGISTER_FEED_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            statement.executeUpdate();
        }
    }

    static FeedVersion feed(Connection connection, FeedId feed) throws SQLException {
        try (var statement = connection.prepareStatement(FIND_FEED_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("Schedule source has not been registered");
                }
                return new FeedVersion(result.getLong(1), result.getObject(2, Long.class));
            }
        }
    }

    static Optional<ScheduleHash> currentHash(Connection connection, FeedId feed) throws SQLException {
        try (var statement = connection.prepareStatement(CURRENT_HASH_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ScheduleHash(result.getString(1))) : Optional.empty();
            }
        }
    }

    static OptionalLong reusable(Connection connection, long feedKey, ScheduleHash hash) throws SQLException {
        try (var statement = connection.prepareStatement(REUSABLE_VERSION_SQL)) {
            statement.setLong(1, feedKey);
            statement.setString(2, hash.value());
            try (var result = statement.executeQuery()) {
                return result.next() ? OptionalLong.of(result.getLong(1)) : OptionalLong.empty();
            }
        }
    }

    static long create(Connection connection, long feedKey, ScheduleHash hash, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement(CREATE_VERSION_SQL)) {
            statement.setLong(1, feedKey);
            statement.setString(2, hash.value());
            statement.setTimestamp(3, Timestamp.from(now));
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    static void activate(Connection connection, FeedVersion feed, long versionKey, Instant now)
            throws SQLException {
        if (feed.currentVersionKey() != null) {
            supersede(connection, feed.currentVersionKey(), now);
        }
        publish(connection, versionKey, now);
        pointCurrent(connection, feed.feedKey(), versionKey);
    }

    private static void supersede(Connection connection, long versionKey, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement(SUPERSEDE_VERSION_SQL)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setLong(2, versionKey);
            statement.executeUpdate();
        }
    }

    private static void publish(Connection connection, long versionKey, Instant now) throws SQLException {
        try (var statement = connection.prepareStatement(PUBLISH_VERSION_SQL)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setLong(2, versionKey);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Cannot publish a version undergoing cleanup");
            }
        }
    }

    private static void pointCurrent(Connection connection, long feedKey, long versionKey) throws SQLException {
        try (var statement = connection.prepareStatement(POINT_CURRENT_VERSION_SQL)) {
            statement.setLong(1, versionKey);
            statement.setLong(2, feedKey);
            statement.executeUpdate();
        }
    }
}
