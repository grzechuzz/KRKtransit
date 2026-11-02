#!/usr/bin/env bash
set -euo pipefail

env_file="$(cd "$(dirname "$0")/.." && pwd)/.env"

if [[ -e "$env_file" ]]; then
    exit 0
fi

secret() {
    head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
}

umask 077
temporary="$(mktemp "$env_file.XXXXXX")"
trap 'rm -f "$temporary"' EXIT

{
    echo "PUBLIC_API_HOST_PORT=8080"
    echo "PUBLIC_API_PROFILE=local"
    echo "PUBLIC_API_ALLOWED_ORIGINS=http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173"
    for name in POSTGRES_ADMIN_PASSWORD SCHEDULE_OWNER_PASSWORD SCHEDULE_RUNTIME_PASSWORD \
        STOP_EVENTS_OWNER_PASSWORD STOP_EVENTS_RUNTIME_PASSWORD REDIS_ADMIN_PASSWORD \
        REDIS_INGESTION_PASSWORD REDIS_STOP_EVENTS_PASSWORD REDIS_LIVE_VEHICLES_PASSWORD \
        SCHEDULE_GRPC_TOKEN STOP_EVENTS_GRPC_TOKEN LIVE_VEHICLES_GRPC_TOKEN PREDICTION_GRPC_TOKEN; do
        echo "$name=$(secret)"
    done
} > "$temporary"

mv "$temporary" "$env_file"
trap - EXIT
