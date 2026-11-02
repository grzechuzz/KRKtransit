\set ON_ERROR_STOP on

GRANT USAGE ON SCHEMA gtfs_schedule TO schedule_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA gtfs_schedule TO schedule_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA gtfs_schedule TO schedule_runtime;
REVOKE ALL ON gtfs_schedule.flyway_schema_history FROM schedule_runtime;

GRANT USAGE ON SCHEMA stop_events TO stop_runtime;
GRANT INSERT, SELECT ON stop_events.events TO stop_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA stop_events TO stop_runtime;
