#!/usr/bin/env bash
# Tear down the hybrid stack: stop host JVMs + docker services (volumes
# retained). Pass --volumes to also drop the dev data.
set -u
cd "$(dirname "$0")/.."

if [ -f .hybrid-host.pids ]; then
  while read -r pid name; do
    kill "$pid" 2>/dev/null && echo "stopped host: $name ($pid)"
  done < .hybrid-host.pids
  sleep 3
  while read -r pid name; do
    kill -9 "$pid" 2>/dev/null || true
  done < .hybrid-host.pids
  rm -f .hybrid-host.pids
fi

ARGS="-f services/docker/docker-compose.dev.yml -f services/docker/docker-compose.hybrid.yml"
if [ "${1:-}" = "--volumes" ]; then
  docker-compose $ARGS down -v
else
  docker-compose $ARGS down
fi
