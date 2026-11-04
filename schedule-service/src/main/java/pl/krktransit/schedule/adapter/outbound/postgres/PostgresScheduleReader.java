package pl.krktransit.schedule.adapter.outbound.postgres;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import pl.krktransit.schedule.application.port.ScheduleReader;
import pl.krktransit.schedule.application.querying.GeometryPoint;
import pl.krktransit.schedule.application.querying.LookupFailure;
import pl.krktransit.schedule.application.querying.ShapeContext;
import pl.krktransit.schedule.application.querying.TripContext;
import pl.krktransit.schedule.application.querying.TripStop;
import pl.krktransit.schedule.domain.feed.FeedId;
import pl.krktransit.schedule.domain.lineroute.RoutePoint;
import pl.krktransit.schedule.domain.lineroute.RouteStop;
import pl.krktransit.schedule.domain.lineroute.RouteVariant;
import pl.krktransit.schedule.domain.lineroute.StopKey;
import pl.krktransit.schedule.domain.schedule.ScheduleHash;

public final class PostgresScheduleReader implements ScheduleReader {
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final String LINE_VARIANTS_SQL = """
            WITH trip_patterns AS (
                SELECT t.version_key, t.trip_id, t.headsign, t.shape_id,
                    array_agg(st.stop_id ORDER BY st.stop_sequence) AS stop_ids
                FROM gtfs_schedule.feeds f
                JOIN gtfs_schedule.trips t ON t.version_key = f.current_version_key
                JOIN gtfs_schedule.routes r ON r.version_key = t.version_key AND r.route_id = t.route_id
                JOIN gtfs_schedule.stop_times st ON st.version_key = t.version_key AND st.trip_id = t.trip_id
                WHERE r.route_short_name = ?
                GROUP BY t.version_key, t.trip_id, t.headsign, t.shape_id
            ), variants AS (
                SELECT version_key, stop_ids, count(*) AS trips_count, min(shape_id) AS shape_id
                FROM trip_patterns GROUP BY version_key, stop_ids
            ), names AS (
                SELECT version_key, stop_ids, coalesce(headsign, '') AS headsign, count(*) AS headsign_count
                FROM trip_patterns GROUP BY version_key, stop_ids, coalesce(headsign, '')
            )
            SELECT v.*, n.headsign, n.headsign_count FROM variants v JOIN names n
                ON n.version_key = v.version_key AND n.stop_ids = v.stop_ids
            ORDER BY v.version_key, v.stop_ids, n.headsign
            """;
    private static final String LINE_STOPS_SQL = """
            SELECT stop_id, stop_name, stop_desc, stop_lat, stop_lon FROM gtfs_schedule.stops
            WHERE version_key = ? AND stop_id = ANY (?)
            """;
    private static final String LINE_SHAPES_SQL = """
            SELECT shape_id, shape_pt_lat, shape_pt_lon FROM gtfs_schedule.shape_points
            WHERE version_key = ? AND shape_id = ANY (?)
            ORDER BY shape_id, shape_pt_sequence, point_key
            """;
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
    public List<RouteVariant> lineRouteVariants(String lineNumber) {
        try (var transaction = PostgresTransaction.read(source)) {
            var connection = transaction.connection();
            var variants = lineVariants(connection, lineNumber);
            var versions = new LinkedHashSet<Long>();
            variants.keySet().forEach(key -> versions.add(key.version()));
            var result = new ArrayList<RouteVariant>();
            for (long version : versions) {
                result.addAll(variantsForVersion(connection, version, variants));
            }
            return List.copyOf(result);
        } catch (SQLException failure) {
            throw PostgresFailures.translate("read line routes", failure);
        }
    }

    private List<RouteVariant> variantsForVersion(Connection connection, long version,
                                                  Map<VariantKey, VariantProjection> variants) throws SQLException {
        var selected = variants.entrySet().stream().filter(entry -> entry.getKey().version() == version).toList();
        var stopIds = new LinkedHashSet<String>();
        var shapeIds = new LinkedHashSet<String>();
        selected.forEach(entry -> {
            stopIds.addAll(entry.getKey().stopIds());
            if (entry.getValue().shapeId != null) shapeIds.add(entry.getValue().shapeId);
        });
        return drawableVariants(selected, lineStops(connection, version, stopIds), lineShapes(connection, version, shapeIds));
    }

    private List<RouteVariant> drawableVariants(List<Map.Entry<VariantKey, VariantProjection>> variants,
                                                Map<String, RouteStop> stops, Map<String, List<RoutePoint>> shapes) {
        var result = new ArrayList<RouteVariant>();
        for (var entry : variants) {
            var projection = entry.getValue();
            var ordered = entry.getKey().stopIds().stream().map(stops::get).toList();
            var points = shapes.getOrDefault(projection.shapeId, List.of());
            if (drawable(ordered, points)) {
                result.add(new RouteVariant(ordered, projection.tripsCount, projection.headsignCounts, points));
            }
        }
        return result;
    }

    private Map<VariantKey, VariantProjection> lineVariants(Connection connection, String line) throws SQLException {
        var variants = new LinkedHashMap<VariantKey, VariantProjection>();
        try (var statement = connection.prepareStatement(LINE_VARIANTS_SQL)) {
            statement.setString(1, line);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var array = rows.getArray("stop_ids");
                    List<String> stops;
                    try { stops = List.of((String[]) array.getArray()); }
                    finally { array.free(); }
                    var key = new VariantKey(rows.getLong("version_key"), stops);
                    var projection = variants.get(key);
                    if (projection == null) {
                        projection = new VariantProjection(rows.getLong("trips_count"), rows.getString("shape_id"));
                        variants.put(key, projection);
                    }
                    projection.headsignCounts.put(rows.getString("headsign"), rows.getLong("headsign_count"));
                }
            }
        }
        return variants;
    }

    private Map<String, RouteStop> lineStops(Connection connection, long version, Set<String> ids) throws SQLException {
        var result = new HashMap<String, RouteStop>();
        var array = connection.createArrayOf("text", ids.toArray(String[]::new));
        try (var statement = connection.prepareStatement(LINE_STOPS_SQL)) {
            statement.setLong(1, version);
            statement.setArray(2, array);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var latitude = rows.getObject("stop_lat", Double.class);
                    var longitude = rows.getObject("stop_lon", Double.class);
                    if (latitude == null || longitude == null) continue;
                    var name = rows.getString("stop_name");
                    var description = rows.getString("stop_desc");
                    result.put(rows.getString("stop_id"), new RouteStop(StopKey.of(name, description), name,
                            description == null ? "" : description, latitude, longitude));
                }
            }
        } finally { array.free(); }
        return result;
    }

    private Map<String, List<RoutePoint>> lineShapes(Connection connection, long version, Set<String> ids) throws SQLException {
        var result = new HashMap<String, List<RoutePoint>>();
        if (ids.isEmpty()) return result;
        var array = connection.createArrayOf("text", ids.toArray(String[]::new));
        try (var statement = connection.prepareStatement(LINE_SHAPES_SQL)) {
            statement.setLong(1, version);
            statement.setArray(2, array);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.computeIfAbsent(rows.getString("shape_id"), ignored -> new ArrayList<>())
                            .add(new RoutePoint(rows.getDouble("shape_pt_lat"), rows.getDouble("shape_pt_lon")));
                }
            }
        } finally { array.free(); }
        return result;
    }

    private static boolean drawable(List<RouteStop> stops, List<RoutePoint> points) {
        return stops.size() >= 2 && stops.stream().allMatch(java.util.Objects::nonNull) && points.size() >= 2;
    }

    private record VariantKey(long version, List<String> stopIds) {}

    private static final class VariantProjection {
        private final long tripsCount;
        private final String shapeId;
        private final Map<String, Long> headsignCounts = new HashMap<>();

        private VariantProjection(long tripsCount, String shapeId) {
            this.tripsCount = tripsCount;
            this.shapeId = shapeId;
        }
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
