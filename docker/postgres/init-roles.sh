#!/usr/bin/env bash
set -euo pipefail

psql --no-psqlrc --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    --set database="$POSTGRES_DB" \
    --set schedule_owner_password="$SCHEDULE_OWNER_PASSWORD" \
    --set schedule_runtime_password="$SCHEDULE_RUNTIME_PASSWORD" \
    --set stop_owner_password="$STOP_EVENTS_OWNER_PASSWORD" \
    --set stop_runtime_password="$STOP_EVENTS_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE schedule_owner LOGIN PASSWORD :'schedule_owner_password';
CREATE ROLE schedule_runtime LOGIN PASSWORD :'schedule_runtime_password';
CREATE ROLE stop_owner LOGIN PASSWORD :'stop_owner_password';
CREATE ROLE stop_runtime LOGIN PASSWORD :'stop_runtime_password';

REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database" TO schedule_owner, schedule_runtime, stop_owner, stop_runtime;
GRANT CREATE ON DATABASE :"database" TO schedule_owner, stop_owner;
SQL
