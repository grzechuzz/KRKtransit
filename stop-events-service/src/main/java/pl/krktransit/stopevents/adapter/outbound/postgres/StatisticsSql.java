package pl.krktransit.stopevents.adapter.outbound.postgres;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import pl.krktransit.stopevents.domain.statistics.Statistic;
import pl.krktransit.stopevents.domain.statistics.StatisticsQuery;

final class StatisticsSql {
    private static final int OBSERVED_METHOD = 1;
    private static final int FIRST_SEQUENCE = 1;
    private static final int SECOND_SEQUENCE = 2;
    private static final int SEQUENCE_STEP = 1;
    private static final int ON_TIME_SECONDS = 120;
    private static final int SLIGHTLY_DELAYED_SECONDS = 360;
    private static final int RANKING_LIMIT = 10;
    // Estimated events are trusted only from the day Python deployed cross-batch validation.
    private static final LocalDate ESTIMATED_VALID_FROM = LocalDate.of(2026, 3, 19);
    private static final String FILTERED = """
            WITH recorded AS NOT MATERIALIZED (
                SELECT e.* FROM stop_events.events e
                WHERE city_id = :city AND source_id IN (:sources) AND line_number = :line
                    AND service_date BETWEEN :start AND :end
            ), filtered AS (
                SELECT * FROM recorded
                WHERE stop_sequence > :first AND stop_sequence < max_stop_sequence
                    AND (detection_method = :observed OR (:estimated AND service_date >= :estimated_from))
            )
            """;
    private static final String MAX_DELAY = FILTERED + """
            , consecutive AS (
                SELECT f.*, delay_seconds - lag(delay_seconds) OVER w AS generated_delay,
                    lag(stop_sequence) OVER w AS prev_sequence, lag(stop_name) OVER w AS prev_name,
                    lag(planned_time) OVER w AS prev_planned, lag(event_time) OVER w AS prev_time,
                    lag(license_plate) OVER w AS prev_plate, lag(detection_method) OVER w AS prev_method
                FROM filtered f WINDOW w AS (PARTITION BY trip_id, service_date ORDER BY stop_sequence)
            ), eligible AS (
                SELECT *, (detection_method <> :observed OR prev_method <> :observed) AS estimated FROM consecutive
                WHERE stop_sequence = prev_sequence + :step AND license_plate = prev_plate
            ), summary AS (
                SELECT EXISTS (SELECT 1 FROM recorded) AS has_data,
                    (SELECT count(DISTINCT (trip_id, service_date)) FROM eligible) AS trips_analyzed
            ), ranked AS (
                SELECT * FROM eligible ORDER BY generated_delay DESC, trip_id, service_date, stop_sequence
                LIMIT :limit
            )
            SELECT s.*, r.* FROM summary s LEFT JOIN ranked r ON true
            ORDER BY generated_delay DESC, trip_id, service_date, stop_sequence
            """;
    private static final String ROUTE_DELAY = FILTERED + """
            , vehicles AS (
                SELECT trip_id, service_date FROM filtered GROUP BY trip_id, service_date
                HAVING count(DISTINCT license_plate) = 1
            ), eligible AS (
                SELECT a.trip_id, a.service_date, a.line_number, a.license_plate, a.headsign,
                    a.stop_name AS first_name, b.stop_name AS last_name,
                    a.planned_time AS first_planned, b.planned_time AS last_planned,
                    a.event_time AS first_time, b.event_time AS last_time,
                    a.delay_seconds AS start_delay, b.delay_seconds AS end_delay,
                    b.delay_seconds - a.delay_seconds AS generated_delay,
                    (a.is_estimated OR b.is_estimated) AS estimated
                FROM filtered a JOIN filtered b ON a.trip_id = b.trip_id AND a.service_date = b.service_date
                    AND b.stop_sequence = a.max_stop_sequence - :step
                JOIN vehicles v ON a.trip_id = v.trip_id AND a.service_date = v.service_date
                WHERE a.stop_sequence = :second
            ), summary AS (
                SELECT EXISTS (SELECT 1 FROM recorded) AS has_data,
                    (SELECT count(DISTINCT (trip_id, service_date)) FROM eligible) AS trips_analyzed
            ), ranked AS (
                SELECT * FROM eligible ORDER BY generated_delay DESC, trip_id, service_date LIMIT :limit
            )
            SELECT s.*, r.* FROM summary s LEFT JOIN ranked r ON true
            ORDER BY generated_delay DESC, trip_id, service_date
            """;
    private static final String PUNCTUALITY = FILTERED + """
            SELECT EXISTS (SELECT 1 FROM recorded) AS has_data, count(*) AS total,
                count(*) FILTER (WHERE delay_seconds <= :on_time) AS on_time,
                count(*) FILTER (WHERE delay_seconds > :on_time AND delay_seconds <= :slight) AS slight,
                count(*) FILTER (WHERE delay_seconds > :slight) AS delayed
            FROM filtered
            """;
    private static final String TREND = FILTERED + """
            , days AS (
                SELECT service_date, round(avg(delay_seconds)::numeric, 1) AS average_delay,
                    count(DISTINCT trip_id) AS trips_count
                FROM filtered GROUP BY service_date
            )
            SELECT EXISTS (SELECT 1 FROM recorded) AS has_data, d.*
            FROM (SELECT 1) s LEFT JOIN days d ON true ORDER BY service_date
            """;
    private static final Map<Statistic, String> STATEMENTS = Map.of(
            Statistic.MAX_DELAY, MAX_DELAY, Statistic.ROUTE_DELAY, ROUTE_DELAY,
            Statistic.PUNCTUALITY, PUNCTUALITY, Statistic.TREND, TREND);

    private StatisticsSql() {}

    static String statement(Statistic statistic) { return STATEMENTS.get(statistic); }

    static MapSqlParameterSource parameters(StatisticsQuery query, StatisticsScope scope) {
        return new MapSqlParameterSource().addValue("city", scope.city()).addValue("sources", scope.sources())
                .addValue("line", query.lineNumber()).addValue("start", query.startDate()).addValue("end", query.endDate())
                .addValue("estimated", query.includeEstimated()).addValue("estimated_from", ESTIMATED_VALID_FROM)
                .addValue("observed", OBSERVED_METHOD).addValue("first", FIRST_SEQUENCE)
                .addValue("second", SECOND_SEQUENCE).addValue("step", SEQUENCE_STEP)
                .addValue("limit", RANKING_LIMIT).addValue("on_time", ON_TIME_SECONDS)
                .addValue("slight", SLIGHTLY_DELAYED_SECONDS);
    }
}
