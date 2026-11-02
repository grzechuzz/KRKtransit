package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import pl.krktransit.stopevents.application.lineevents.LineEventReader;
import pl.krktransit.stopevents.application.lineevents.LineEventsFailure;
import pl.krktransit.stopevents.domain.event.DetectionMethod;
import pl.krktransit.stopevents.domain.lineevents.LineEvent;
import pl.krktransit.stopevents.domain.lineevents.LineEventsPage;
import pl.krktransit.stopevents.domain.lineevents.LineEventsQuery;

public final class JdbcLineEventsReader implements LineEventReader {
    private static final String LINE_EVENTS = """
            SELECT id, source_id, trip_id, service_date, stop_sequence, max_stop_sequence, stop_id, stop_name,
                stop_desc, planned_time, event_time, delay_seconds, detection_method, is_estimated, created_at, headsign
            FROM stop_events.events
            WHERE city_id = :city AND line_number = :line AND (created_at, id) > (:created_after, :after_id)
            ORDER BY created_at, id
            LIMIT :limit
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLineEventsReader(DataSource source) { jdbc = new NamedParameterJdbcTemplate(source); }

    @Override
    public LineEventsPage read(LineEventsQuery query) {
        try { return page(jdbc.query(LINE_EVENTS, parameters(query), (rows, index) -> event(rows)), query.pageSize()); }
        catch (DataAccessException failure) { throw translate(failure); }
    }

    private MapSqlParameterSource parameters(LineEventsQuery query) {
        return new MapSqlParameterSource().addValue("city", query.cityId()).addValue("line", query.lineNumber())
                .addValue("created_after", storedPrecision(query.createdAfter()))
                .addValue("after_id", query.afterId().orElse(Long.MAX_VALUE))
                .addValue("limit", query.pageSize() + 1);
    }

    private OffsetDateTime storedPrecision(Instant time) {
        return OffsetDateTime.ofInstant(time.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private LineEventsPage page(List<LineEvent> rows, int pageSize) {
        boolean hasMore = rows.size() > pageSize;
        return new LineEventsPage(hasMore ? rows.subList(0, pageSize) : rows, hasMore);
    }

    private LineEvent event(ResultSet rows) throws SQLException {
        return new LineEvent(rows.getLong("id"), rows.getString("source_id"), rows.getString("trip_id"),
                rows.getObject("service_date", LocalDate.class), rows.getInt("stop_sequence"),
                rows.getInt("max_stop_sequence"), rows.getString("stop_id"), rows.getString("stop_name"),
                rows.getString("stop_desc"), instant(rows, "planned_time"), instant(rows, "event_time"),
                rows.getInt("delay_seconds"), DetectionMethod.fromCode(rows.getInt("detection_method")),
                rows.getBoolean("is_estimated"), instant(rows, "created_at"), rows.getString("headsign"));
    }

    private Instant instant(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private RuntimeException translate(DataAccessException failure) {
        if (unavailable(failure)) {
            return new LineEventsFailure(LineEventsFailure.Kind.UNAVAILABLE, "Line event storage is unavailable", failure);
        }
        return new IllegalStateException("Line event SQL execution failed", failure);
    }

    private boolean unavailable(DataAccessException failure) {
        return failure instanceof DataAccessResourceFailureException || failure instanceof TransientDataAccessResourceException;
    }
}
