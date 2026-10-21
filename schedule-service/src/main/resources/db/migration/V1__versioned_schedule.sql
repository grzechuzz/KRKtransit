CREATE SCHEMA IF NOT EXISTS gtfs_schedule;

CREATE TABLE gtfs_schedule.feeds (
    feed_key BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    city_id TEXT NOT NULL,
    source_id TEXT NOT NULL,
    current_version_key BIGINT,
    UNIQUE (city_id, source_id)
);

CREATE TABLE gtfs_schedule.schedule_versions (
    version_key BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    feed_key BIGINT NOT NULL REFERENCES gtfs_schedule.feeds (feed_key),
    zip_hash TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    superseded_at TIMESTAMPTZ,
    deleting_at TIMESTAMPTZ,
    UNIQUE (version_key, feed_key)
);

CREATE UNIQUE INDEX schedule_versions_reusable_hash
    ON gtfs_schedule.schedule_versions (feed_key, zip_hash)
    WHERE deleting_at IS NULL;

CREATE INDEX schedule_versions_hash
    ON gtfs_schedule.schedule_versions (zip_hash)
    WHERE deleting_at IS NULL;

CREATE INDEX schedule_versions_expiry
    ON gtfs_schedule.schedule_versions (superseded_at, version_key)
    WHERE superseded_at IS NOT NULL;

ALTER TABLE gtfs_schedule.feeds ADD CONSTRAINT feeds_current_version_owner
    FOREIGN KEY (current_version_key, feed_key)
    REFERENCES gtfs_schedule.schedule_versions (version_key, feed_key);

CREATE TABLE gtfs_schedule.routes (
    version_key BIGINT NOT NULL REFERENCES gtfs_schedule.schedule_versions (version_key),
    route_id TEXT NOT NULL,
    route_short_name TEXT NOT NULL,
    PRIMARY KEY (version_key, route_id)
);

CREATE TABLE gtfs_schedule.stops (
    version_key BIGINT NOT NULL REFERENCES gtfs_schedule.schedule_versions (version_key),
    stop_id TEXT NOT NULL,
    stop_name TEXT NOT NULL,
    stop_code TEXT,
    stop_desc TEXT,
    stop_lat DOUBLE PRECISION,
    stop_lon DOUBLE PRECISION,
    PRIMARY KEY (version_key, stop_id)
);

CREATE TABLE gtfs_schedule.trips (
    version_key BIGINT NOT NULL REFERENCES gtfs_schedule.schedule_versions (version_key),
    trip_id TEXT NOT NULL,
    route_id TEXT NOT NULL,
    service_id TEXT NOT NULL,
    direction_id SMALLINT,
    headsign TEXT,
    shape_id TEXT,
    PRIMARY KEY (version_key, trip_id),
    FOREIGN KEY (version_key, route_id)
        REFERENCES gtfs_schedule.routes (version_key, route_id)
);

CREATE INDEX trips_lookup ON gtfs_schedule.trips (trip_id, version_key);
CREATE INDEX trips_route ON gtfs_schedule.trips (version_key, route_id);

CREATE TABLE gtfs_schedule.stop_times (
    version_key BIGINT NOT NULL REFERENCES gtfs_schedule.schedule_versions (version_key),
    trip_id TEXT NOT NULL,
    stop_sequence INTEGER NOT NULL,
    stop_id TEXT NOT NULL,
    arrival_seconds INTEGER NOT NULL,
    departure_seconds INTEGER NOT NULL,
    PRIMARY KEY (version_key, trip_id, stop_sequence),
    FOREIGN KEY (version_key, trip_id)
        REFERENCES gtfs_schedule.trips (version_key, trip_id),
    FOREIGN KEY (version_key, stop_id)
        REFERENCES gtfs_schedule.stops (version_key, stop_id)
);

CREATE INDEX stop_times_stop ON gtfs_schedule.stop_times (version_key, stop_id);

CREATE TABLE gtfs_schedule.shape_points (
    point_key BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version_key BIGINT NOT NULL REFERENCES gtfs_schedule.schedule_versions (version_key),
    shape_id TEXT NOT NULL,
    shape_pt_lat DOUBLE PRECISION NOT NULL,
    shape_pt_lon DOUBLE PRECISION NOT NULL,
    shape_pt_sequence INTEGER NOT NULL
);

CREATE INDEX shape_points_lookup
    ON gtfs_schedule.shape_points (version_key, shape_id, shape_pt_sequence, point_key);
