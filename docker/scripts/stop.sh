#!/bin/bash
# ============================================
# Stop Bhukkad Server
# Usage: ./scripts/stop.sh [dev|prod] [--clean]
# ============================================

ENV=${1:-dev}
CLEAN=${2:-}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

case $ENV in
    dev) COMPOSE_FILE="$DOCKER_DIR/docker-compose.dev.yml" ;;
    prod) COMPOSE_FILE="$DOCKER_DIR/docker-compose.prod.yml" ;;
    *) echo "Usage: ./stop.sh [dev|prod] [--clean]"; exit 1 ;;
esac

echo "🛑 Stopping Bhukkad Server [$ENV]..."

if [ "$CLEAN" = "--clean" ]; then
    echo "⚠️  Removing containers and volumes..."
    docker-compose -f "$COMPOSE_FILE" down -v --remove-orphans
    echo "✅ All containers and volumes removed"
else
    docker-compose -f "$COMPOSE_FILE" down
    echo "✅ All containers stopped"
fi