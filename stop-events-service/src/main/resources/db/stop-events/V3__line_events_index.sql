CREATE INDEX events_line_created
    ON stop_events.events (city_id, line_number, created_at, id);
