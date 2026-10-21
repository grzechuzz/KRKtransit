#!/usr/bin/env bash
set -euo pipefail

# Testcontainers in the Java integration suite owns all disposable resources.
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"
exec bash ./mvnw -B -Pintegration verify "$@"
