CREATE INDEX events_statistics_line_date
    ON stop_events.events (city_id, line_number, service_date)
    INCLUDE (source_id, trip_id, stop_sequence, max_stop_sequence, delay_seconds, detection_method);
