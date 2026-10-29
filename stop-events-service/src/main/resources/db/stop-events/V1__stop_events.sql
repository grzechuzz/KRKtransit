CREATE TABLE stop_events.events (
    id bigint GENERATED ALWAYS AS IDENTITY,
    service_date date NOT NULL,
    city_id text NOT NULL,
    source_id text NOT NULL,
    trip_id text NOT NULL,
    stop_sequence integer NOT NULL,
    stop_id text NOT NULL,
    line_number text NOT NULL,
    stop_name text NOT NULL,
    stop_desc text,
    direction_id integer NOT NULL,
    headsign text NOT NULL CHECK (headsign ~ '[^[:space:]]'),
    planned_time timestamptz NOT NULL,
    event_time timestamptz NOT NULL,
    delay_seconds integer GENERATED ALWAYS AS
        (trunc(extract(epoch FROM event_time - planned_time))::integer) STORED,
    vehicle_id text,
    license_plate text NOT NULL CHECK (license_plate ~ '[^[:space:]]'),
    detection_method smallint NOT NULL CHECK (detection_method IN (1, 2, 3)),
    is_estimated boolean NOT NULL,
    static_hash text NOT NULL,
    max_stop_sequence integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (id, service_date),
    UNIQUE (trip_id, service_date, stop_sequence)
) PARTITION BY RANGE (service_date);

DO $$
DECLARE
    month_start date := DATE '2026-01-01';
    next_month date;
BEGIN
    WHILE month_start < DATE '2030-01-01' LOOP
        next_month := (month_start + INTERVAL '1 month')::date;
        EXECUTE format('CREATE TABLE stop_events.%I PARTITION OF stop_events.events FOR VALUES FROM (%L) TO (%L)',
            'events_' || to_char(month_start, 'YYYY_MM'), month_start, next_month);
        month_start := next_month;
    END LOOP;
END $$;

CREATE TABLE stop_events.events_default PARTITION OF stop_events.events DEFAULT;
