package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import pl.krktransit.stopevents.application.port.StateIntegrityFailure;
import pl.krktransit.stopevents.application.port.StopEventStore;
import pl.krktransit.stopevents.application.port.StorageFailure;
import pl.krktransit.stopevents.application.port.StoredStopConflict;
import pl.krktransit.stopevents.domain.event.EventKey;
import pl.krktransit.stopevents.domain.event.StopEvent;
import pl.krktransit.stopevents.domain.state.SavedStop;

public final class JdbcStopEventStore implements StopEventStore {
    private static final String INSERT = """
            INSERT INTO stop_events.events
            (city_id, source_id, trip_id, service_date, stop_sequence, stop_id, line_number, stop_name,
             stop_desc, direction_id, headsign, planned_time, event_time, vehicle_id, license_plate,
             detection_method, is_estimated, static_hash, max_stop_sequence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trip_id, service_date, stop_sequence) DO NOTHING
            RETURNING delay_seconds, event_time
            """;
    private static final String EXISTING = """
            SELECT delay_seconds, event_time FROM stop_events.events
            WHERE trip_id = ? AND service_date = ? AND stop_sequence = ?
            """;
    private static final String RECOVERY = """
            SELECT trip_id, service_date, stop_sequence, delay_seconds, event_time
            FROM stop_events.events WHERE trip_id = ANY (?) AND service_date >= ?
            """;
    private final DataSource source;

    public JdbcStopEventStore(DataSource source) { this.source = source; }

    @Override
    public void checkAvailable() {
        try (var connection = source.getConnection()) {
            if (!connection.isValid(2)) throw new StorageFailure(StorageFailure.Kind.POSTGRES, "Invalid event connection", null);
        } catch (SQLException failure) { throw translate(failure); }
    }

    @Override
    public Map<EventKey, SavedStop> storedStops(Set<String> trips, LocalDate since) {
        if (trips.isEmpty()) return Map.of();
        try (var connection = source.getConnection(); var query = connection.prepareStatement(RECOVERY)) {
            var ids = connection.createArrayOf("text", trips.toArray(String[]::new));
            try {
                query.setArray(1, ids);
                query.setObject(2, since);
                return readStored(query);
            } finally { ids.free(); }
        } catch (SQLException failure) { throw translate(failure); }
    }

    private Map<EventKey, SavedStop> readStored(PreparedStatement query) throws SQLException {
        var saved = new HashMap<EventKey, SavedStop>();
        try (var rows = query.executeQuery()) {
            while (rows.next()) {
                var key = new EventKey(rows.getString("trip_id"), rows.getObject("service_date", LocalDate.class),
                        rows.getInt("stop_sequence"));
                saved.put(key, savedStop(rows));
            }
        }
        return Map.copyOf(saved);
    }

    @Override
    public Map<EventKey, SavedStop> insert(List<StopEvent> events) {
        if (events.isEmpty()) return Map.of();
        try (var transaction = EventTransaction.open(source);
             var insert = transaction.connection().prepareStatement(INSERT);
             var existing = transaction.connection().prepareStatement(EXISTING)) {
            var stored = new HashMap<EventKey, SavedStop>();
            var conflicts = new HashMap<EventKey, SavedStop>();
            for (var event : events) insert(event, insert, existing, stored, conflicts);
            if (!conflicts.isEmpty()) throw new StoredStopConflict(conflicts);
            transaction.commit();
            return Map.copyOf(stored);
        } catch (SQLException failure) { throw translate(failure); }
    }

    private void insert(StopEvent event, PreparedStatement insert, PreparedStatement existing,
                         Map<EventKey, SavedStop> stored, Map<EventKey, SavedStop> conflicts) throws SQLException {
        bind(insert, event);
        SavedStop value;
        try (var rows = insert.executeQuery()) {
            value = rows.next() ? savedStop(rows) : null;
        }
        if (value == null) value = existing(existing, event.key());
        stored.put(event.key(), value);
        if (!value.equals(event.savedStop())) conflicts.put(event.key(), value);
    }

    private SavedStop existing(PreparedStatement query, EventKey key) throws SQLException {
        query.setString(1, key.tripId());
        query.setObject(2, key.serviceDate());
        query.setInt(3, key.sequence());
        try (var rows = query.executeQuery()) {
            if (!rows.next()) throw new StateIntegrityFailure("Conflicting stop event disappeared");
            return savedStop(rows);
        }
    }

    private SavedStop savedStop(ResultSet row) throws SQLException {
        return new SavedStop(row.getInt("delay_seconds"), row.getObject("event_time", OffsetDateTime.class).toInstant());
    }

    private void bind(PreparedStatement statement, StopEvent event) throws SQLException {
        statement.setString(1, event.source().cityId());
        statement.setString(2, event.source().sourceId());
        statement.setString(3, event.tripId());
        statement.setObject(4, event.serviceDate());
        statement.setInt(5, event.sequence());
        statement.setString(6, event.stopId());
        statement.setString(7, event.lineNumber());
        statement.setString(8, event.stopName());
        statement.setString(9, event.stopDescription());
        statement.setInt(10, event.directionId());
        statement.setString(11, event.headsign());
        statement.setObject(12, event.plannedTime().atOffset(ZoneOffset.UTC));
        statement.setObject(13, event.eventTime().atOffset(ZoneOffset.UTC));
        statement.setString(14, event.vehicleId());
        statement.setString(15, event.licensePlate());
        statement.setInt(16, event.method().code());
        statement.setBoolean(17, event.estimated());
        statement.setString(18, event.staticHash());
        statement.setInt(19, event.maxSequence());
    }

    private RuntimeException translate(SQLException failure) {
        if (integrityViolation(failure.getSQLState())) {
            return new StateIntegrityFailure("Stop-event schema or invariant violation", failure);
        }
        return new StorageFailure(StorageFailure.Kind.POSTGRES, "Stop-event PostgreSQL is unavailable", failure);
    }

    private boolean integrityViolation(String state) {
        return state != null && (state.startsWith("23") || state.startsWith("42"));
    }
}
