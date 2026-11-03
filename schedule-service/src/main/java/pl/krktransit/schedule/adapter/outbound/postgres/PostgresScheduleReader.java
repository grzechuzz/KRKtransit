package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.application.querying.GeometryPoint;
import pl.krktransit.schedule.application.querying.LookupFailure;
import pl.krktransit.schedule.application.querying.ShapeContext;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.application.querying.TripStop;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class PostgresScheduleReader implements ScheduleReader {
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final String AVAILABLE_VERSION = "v.zip_hash = ? AND v.deleting_at IS NULL "
            + "AND (v.superseded_at IS NULL OR v.superseded_at > ?)";
    private static final String CURRENT_HASH_SQL = "SELECT v.zip_hash FROM gtfs_schedule.feeds f "
            + "LEFT JOIN gtfs_schedule.schedule_versions v ON v.version_key = f.current_version_key "
            + "WHERE f.city_id = ? AND f.source_id = ?";
    private static final String FIND_TRIP_SQL = "SELECT v.version_key, v.zip_hash, t.route_id, r.route_short_name, t.direction_id, "
            + "t.headsign, t.shape_id FROM gtfs_schedule.schedule_versions v "
            + "JOIN gtfs_schedule.trips t ON t.version_key = v.version_key "
            + "JOIN gtfs_schedule.routes r ON r.version_key = t.version_key AND r.route_id = t.route_id "
            + "WHERE " + AVAILABLE_VERSION + " AND t.trip_id = ?";
    private static final String TRIP_STOPS_SQL = "SELECT s.stop_id, s.stop_name, s.stop_desc, s.stop_lat, s.stop_lon, "
            + "st.stop_sequence, st.arrival_seconds, st.departure_seconds "
            + "FROM gtfs_schedule.stop_times st JOIN gtfs_schedule.stops s "
            + "ON s.version_key = st.version_key AND s.stop_id = st.stop_id "
            + "WHERE st.version_key = ? AND st.trip_id = ? ORDER BY st.stop_sequence";
    private static final String SHAPE_POINTS_SQL = "SELECT p.shape_pt_lat, p.shape_pt_lon, p.shape_pt_sequence "
            + "FROM gtfs_schedule.schedule_versions v JOIN gtfs_schedule.shape_points p "
            + "ON p.version_key = v.version_key WHERE " + AVAILABLE_VERSION
            + " AND p.shape_id = ? ORDER BY p.shape_pt_sequence, p.point_key";
    private static final String AVAILABLE_HASH_SQL = "SELECT 1 FROM gtfs_schedule.schedule_versions v "
            + "JOIN gtfs_schedule.feeds f ON f.feed_key = v.feed_key WHERE " + AVAILABLE_VERSION
            + " AND starts_with(?, f.city_id || ':' || f.source_id || ':') LIMIT 1";

    private final DataSource source;
    private final Clock clock;

    public PostgresScheduleReader(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public ScheduleHash currentHash(FeedId feed) {
        try (var transaction = PostgresTransaction.read(source);
                var statement = transaction.connection().prepareStatement(CURRENT_HASH_SQL)) {
            statement.setString(1, feed.cityId());
            statement.setString(2, feed.sourceId());
            try (var result = statement.executeQuery()) {
                return readPublishedHash(result, feed);
            }
        } catch (SQLException failure) {
            throw PostgresFailures.translate("read published hash", failure);
        }
    }

    private static ScheduleHash readPublishedHash(ResultSet result, FeedId feed) throws SQLException {
        if (!result.next()) {
            throw new LookupFailure(LookupFailure.Kind.UNKNOWN_FEED, "Unknown schedule source: " + feed);
        }
        String hash = result.getString(1);
        if (hash == null) {
            throw new LookupFailure(LookupFailure.Kind.NOT_PUBLISHED, "Source has no published schedule: " + feed);
        }
        return new ScheduleHash(hash);
    }

    @Override
    public TripContext tripContext(ScheduleHash hash, String tripId) {
        Instant cutoff = clock.instant().minus(RETENTION);
        try (var transaction = PostgresTransaction.read(source)) {
            Connection connection = transaction.connection();
            TripProjection trip = findTrip(connection, hash, tripId, cutoff);
            List<TripStop> stops = tripStops(connection, trip.versionKey(), tripId);
            int maximum = stops.stream().mapToInt(TripStop::sequence).max()
                    .orElseThrow(() -> entityMissing("Trip has no scheduled stops: " + tripId));
            return new TripContext(trip.hash(), tripId, trip.routeId(), trip.lineNumber(), trip.directionId(),
                    trip.headsign(), trip.shapeId(), maximum, stops);
        } catch (SQLException failure) {
            throw PostgresFailures.translate("read trip context", failure);
        }
    }

    private TripProjection findTrip(Connection connection, ScheduleHash hash, String tripId, Instant cutoff)
            throws SQLException {
        try (var statement = connection.prepareStatement(FIND_TRIP_SQL)) {
            statement.setString(1, hash.value());
            statement.setTimestamp(2, Timestamp.from(cutoff));
            statement.setString(3, tripId);
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    return tripProjection(result);
                }
            }
        }
        requireAvailableHash(connection, hash, tripId, cutoff);
        throw entityMissing("Trip not found in requested schedule: " + tripId);
    }

    private static TripProjection tripProjection(ResultSet result) throws SQLException {
        return new TripProjection(result.getLong("version_key"),
                new ScheduleHash(result.getString("zip_hash")), result.getString("route_id"),
                result.getString("route_short_name"), result.getObject("direction_id", Integer.class),
                result.getString("headsign"), result.getString("shape_id"));
    }

    private List<TripStop> tripStops(Connection connection, long versionKey, String tripId) throws SQLException {
        var stops = new ArrayList<TripStop>();
        try (var statement = connection.prepareStatement(TRIP_STOPS_SQL)) {
            statement.setLong(1, versionKey);
            statement.setString(2, tripId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    stops.add(tripStop(result));
                }
            }
        }
        return stops;
    }

    private static TripStop tripStop(ResultSet result) throws SQLException {
        return new TripStop(result.getString("stop_id"), result.getString("stop_name"),
                result.getString("stop_desc"), result.getObject("stop_lat", Double.class),
                result.getObject("stop_lon", Double.class), result.getInt("stop_sequence"),
                result.getInt("arrival_seconds"), result.getInt("departure_seconds"));
    }

    @Override
    public ShapeContext shape(ScheduleHash hash, String shapeId) {
        Instant cutoff = clock.instant().minus(RETENTION);
        try (var transaction = PostgresTransaction.read(source)) {
            List<GeometryPoint> points = shapePoints(transaction.connection(), hash, shapeId, cutoff);
            if (points.isEmpty()) {
                requireAvailableHash(transaction.connection(), hash, shapeId, cutoff);
                throw entityMissing("Shape not found in requested schedule: " + shapeId);
            }
            return new ShapeContext(hash, shapeId, points);
        } catch (SQLException failure) {
            throw PostgresFailures.translate("read shape", failure);
        }
    }

    private List<GeometryPoint> shapePoints(Connection connection, ScheduleHash hash, String shapeId,
                                           Instant cutoff) throws SQLException {
        var points = new ArrayList<GeometryPoint>();
        try (var statement = connection.prepareStatement(SHAPE_POINTS_SQL)) {
            statement.setString(1, hash.value());
            statement.setTimestamp(2, Timestamp.from(cutoff));
            statement.setString(3, shapeId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    points.add(new GeometryPoint(result.getDouble(1), result.getDouble(2), result.getInt(3)));
                }
            }
        }
        return points;
    }

    private void requireAvailableHash(Connection connection, ScheduleHash hash, String entityId, Instant cutoff)
            throws SQLException {
        try (var statement = connection.prepareStatement(AVAILABLE_HASH_SQL)) {
            statement.setString(1, hash.value());
            statement.setTimestamp(2, Timestamp.from(cutoff));
            statement.setString(3, entityId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new LookupFailure(LookupFailure.Kind.VERSION_UNAVAILABLE,
                            "Requested schedule version is unavailable: " + hash.value());
                }
            }
        }
    }

    private static LookupFailure entityMissing(String message) {
        return new LookupFailure(LookupFailure.Kind.ENTITY_NOT_FOUND, message);
    }

    private record TripProjection(long versionKey, ScheduleHash hash, String routeId, String lineNumber,
                                  Integer directionId, String headsign, String shapeId) {}
}
