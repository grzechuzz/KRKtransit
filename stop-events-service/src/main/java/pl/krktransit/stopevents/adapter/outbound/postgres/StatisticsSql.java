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
            , trips AS (
                SELECT trip_id, service_date, min(license_plate) AS license_plate, max(license_plate) AS last_plate,
                    max(max_stop_sequence) FILTER (WHERE stop_sequence = :second) AS first_max_sequence,
                    max(stop_sequence) FILTER (WHERE stop_sequence = max_stop_sequence - :step) AS last_sequence,
                    max(line_number) FILTER (WHERE stop_sequence = :second) AS line_number,
                    max(headsign) FILTER (WHERE stop_sequence = :second) AS headsign,
                    max(stop_name) FILTER (WHERE stop_sequence = :second) AS first_name,
                    max(stop_name) FILTER (WHERE stop_sequence = max_stop_sequence - :step) AS last_name,
                    max(planned_time) FILTER (WHERE stop_sequence = :second) AS first_planned,
                    max(planned_time) FILTER (WHERE stop_sequence = max_stop_sequence - :step) AS last_planned,
                    max(event_time) FILTER (WHERE stop_sequence = :second) AS first_time,
                    max(event_time) FILTER (WHERE stop_sequence = max_stop_sequence - :step) AS last_time,
                    max(delay_seconds) FILTER (WHERE stop_sequence = :second) AS start_delay,
                    max(delay_seconds) FILTER (WHERE stop_sequence = max_stop_sequence - :step) AS end_delay,
                    bool_or(is_estimated) FILTER (WHERE stop_sequence = :second
                        OR stop_sequence = max_stop_sequence - :step) AS estimated
                FROM filtered GROUP BY trip_id, service_date
            ), eligible AS (
                SELECT trip_id, service_date, line_number, license_plate, headsign, first_name, last_name,
                    first_planned, last_planned, first_time, last_time, start_delay, end_delay,
                    end_delay - start_delay AS generated_delay, estimated
                FROM trips WHERE license_plate = last_plate AND last_sequence = first_max_sequence - :step
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
