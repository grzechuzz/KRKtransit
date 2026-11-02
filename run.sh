#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
./scripts/generate-env.sh
docker compose up --detach --build --wait "$@"
port="$(grep '^PUBLIC_API_HOST_PORT=' .env | cut -d= -f2)"
echo "Public API: http://127.0.0.1:$port"
