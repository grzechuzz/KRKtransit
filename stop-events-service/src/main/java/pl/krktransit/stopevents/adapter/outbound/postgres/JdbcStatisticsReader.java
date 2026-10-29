package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import pl.krktransit.stopevents.application.statistics.QueryCancellation;
import pl.krktransit.stopevents.application.statistics.StatisticsFailure;
import pl.krktransit.stopevents.application.statistics.StatisticsReader;
import pl.krktransit.stopevents.domain.source.SourceId;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult;
import pl.krktransit.stopevents.domain.statistics.StatisticsResult.*;

public final class JdbcStatisticsReader implements StatisticsReader {
    private static final System.Logger LOG = System.getLogger(JdbcStatisticsReader.class.getName());
    private final NamedParameterJdbcTemplate jdbc;
    private final StatisticsScope scope;

    public JdbcStatisticsReader(DataSource source, Set<SourceId> sources) {
        jdbc = new NamedParameterJdbcTemplate(source);
        scope = StatisticsScope.of(sources);
    }

    @Override
    public StatisticsResult read(Statistic statistic, StatisticsQuery query, QueryCancellation cancellation) {
        cancellation.check();
        try {
            return jdbc.execute(StatisticsSql.statement(statistic), StatisticsSql.parameters(query, scope), statement -> {
                try (var registration = cancellation.onCancel(() -> cancel(statement))) {
                    cancellation.check();
                    try (var rows = statement.executeQuery()) { return result(statistic, rows, cancellation); }
                }
            });
        } catch (DataAccessException failure) {
            cancellation.check();
            throw translate(failure);
        }
    }

    private StatisticsResult result(Statistic statistic, ResultSet rows, QueryCancellation cancellation) throws SQLException {
        if (!rows.next()) throw new IllegalStateException("Statistics query did not return its summary");
        if (!rows.getBoolean("has_data")) throw new StatisticsFailure(StatisticsFailure.Kind.NOT_FOUND, "No statistics data for line and date range");
        cancellation.check();
        return switch (statistic) {
            case MAX_DELAY -> maxDelay(rows, cancellation);
            case ROUTE_DELAY -> routeDelay(rows, cancellation);
            case PUNCTUALITY -> new Punctuality(rows.getLong("total"), rows.getLong("on_time"),
                    rows.getLong("slight"), rows.getLong("delayed"));
            case TREND -> trend(rows, cancellation);
        };
    }

    private MaxDelay maxDelay(ResultSet rows, QueryCancellation cancellation) throws SQLException {
        long trips = rows.getLong("trips_analyzed");
        var segments = new ArrayList<Segment>();
        do {
            cancellation.check();
            if (rows.getString("trip_id") != null) segments.add(segment(rows));
        } while (rows.next());
        return new MaxDelay(trips, segments);
    }

    private Segment segment(ResultSet rows) throws SQLException {
        return new Segment(rows.getString("trip_id"), date(rows), rows.getString("line_number"),
                rows.getString("license_plate"), rows.getString("prev_name"), rows.getString("stop_name"),
                rows.getInt("prev_sequence"), rows.getInt("stop_sequence"), instant(rows, "prev_planned"),
                instant(rows, "prev_time"), instant(rows, "planned_time"), instant(rows, "event_time"),
                rows.getInt("generated_delay"), rows.getString("headsign"), rows.getBoolean("estimated"));
    }

    private RouteDelay routeDelay(ResultSet rows, QueryCancellation cancellation) throws SQLException {
        long trips = rows.getLong("trips_analyzed");
        var routes = new ArrayList<Route>();
        do {
            cancellation.check();
            if (rows.getString("trip_id") != null) routes.add(route(rows));
        } while (rows.next());
        return new RouteDelay(trips, routes);
    }

    private Route route(ResultSet rows) throws SQLException {
        return new Route(rows.getString("trip_id"), date(rows), rows.getString("line_number"),
                rows.getString("license_plate"), rows.getString("first_name"), rows.getString("last_name"),
                instant(rows, "first_planned"), instant(rows, "first_time"), instant(rows, "last_planned"),
                instant(rows, "last_time"), rows.getInt("start_delay"), rows.getInt("end_delay"),
                rows.getInt("generated_delay"), rows.getString("headsign"), rows.getBoolean("estimated"));
    }

    private Trend trend(ResultSet rows, QueryCancellation cancellation) throws SQLException {
        var days = new ArrayList<Day>();
        do {
            cancellation.check();
            if (rows.getObject("service_date") != null) {
                days.add(new Day(date(rows), rows.getDouble("average_delay"), rows.getLong("trips_count")));
            }
        } while (rows.next());
        return new Trend(days);
    }

    private LocalDate date(ResultSet rows) throws SQLException { return rows.getObject("service_date", LocalDate.class); }

    private Instant instant(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private RuntimeException translate(DataAccessException failure) {
        if (unavailable(failure)) return new StatisticsFailure(StatisticsFailure.Kind.UNAVAILABLE, "Statistics storage is unavailable", failure);
        return new IllegalStateException("Statistics SQL execution failed", failure);
    }

    private boolean unavailable(DataAccessException failure) {
        return failure instanceof DataAccessResourceFailureException || failure instanceof TransientDataAccessResourceException;
    }

    private void cancel(PreparedStatement statement) {
        try { statement.cancel(); }
        catch (SQLException failure) { LOG.log(System.Logger.Level.WARNING, "Could not cancel statistics SQL", failure); }
    }
}
