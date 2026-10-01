#!/usr/bin/env bash
# Stop the lite local stack started by local-lite.sh.
# Leaves the postgres volume intact so you don't lose data between sessions.
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose -f services/docker/docker-compose.lite.yml down
