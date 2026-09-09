#!/usr/bin/env bash
# Stop the host-JVM local stack started by local-up.sh (logs stay under
# /tmp/bhukkad-local). The dockerized postgres/redis in services/docker are
# left running on purpose (tear them down with docker compose down -v).
set -u
cd "$(dirname "$0")/.."
if [ -f .local-stack.pids ]; then
  while read -r pid name; do
    if kill -0 "$pid" 2>/dev/null; then
      echo "stopping $name (pid $pid)"
      kill "$pid" 2>/dev/null || true
    fi
  done < .local-stack.pids
  # wait, then force-kill stragglers
  sleep 5
  while read -r pid name; do
    kill -9 "$pid" 2>/dev/null || true
  done < .local-stack.pids
  rm -f .local-stack.pids
else
  echo "no .local-stack.pids; nothing to stop"
fi
