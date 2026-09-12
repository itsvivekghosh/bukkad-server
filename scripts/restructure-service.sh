#!/bin/bash
# Standard Service Restructuring Script
# Applies the standard folder architecture to a service
# Usage: ./restructure-service.sh <service-name>

set -e

SERVICE_NAME=$1
if [ -z "$SERVICE_NAME" ]; then
    echo "Usage: $0 <service-name>"
    echo "Example: $0 restaurant"
    exit 1
fi

BASE_DIR="/Users/vivekghosh/Documents/vivekghosh/bhukkad/backend-server/services"
SERVICE_DIR="$BASE_DIR/$SERVICE_NAME/src/main/java/com/bhukkad/$SERVICE_NAME"
TEST_DIR="$BASE_DIR/$SERVICE_NAME/src/test/java/com/bhukkad/$SERVICE_NAME"

echo "Restructuring service: $SERVICE_NAME"
echo "Service dir: $SERVICE_DIR"
echo "Test dir: $TEST_DIR"

# Create standard main directory structure
echo "Creating main directory structure..."
mkdir -p "$SERVICE_DIR/config"
mkdir -p "$SERVICE_DIR/api/controller"
mkdir -p "$SERVICE_DIR/api/dto/request"
mkdir -p "$SERVICE_DIR/api/dto/response"
mkdir -p "$SERVICE_DIR/api/exception"
mkdir -p "$SERVICE_DIR/domain/entity"
mkdir -p "$SERVICE_DIR/domain/repository"
mkdir -p "$SERVICE_DIR/domain/service"
mkdir -p "$SERVICE_DIR/domain/service/impl"
mkdir -p "$SERVICE_DIR/domain/event"
mkdir -p "$SERVICE_DIR/domain/mapper"
mkdir -p "$SERVICE_DIR/infrastructure/client"
mkdir -p "$SERVICE_DIR/infrastructure/persistence"
mkdir -p "$SERVICE_DIR/infrastructure/messaging"
mkdir -p "$SERVICE_DIR/infrastructure/cache"
mkdir -p "$SERVICE_DIR/infrastructure/ratelimit"
mkdir -p "$SERVICE_DIR/util"

# Create standard test directory structure
echo "Creating test directory structure..."
mkdir -p "$TEST_DIR/unit/service"
mkdir -p "$TEST_DIR/unit/controller"
mkdir -p "$TEST_DIR/unit/domain"
mkdir -p "$TEST_DIR/integration/controller"
mkdir -p "$TEST_DIR/integration/repository"
mkdir -p "$TEST_DIR/integration/messaging"
mkdir -p "$TEST_DIR/architecture"
mkdir -p "$TEST_DIR/support"

echo "Directory structure created for $SERVICE_NAME"
echo ""
echo "Next steps for manual restructuring:"
echo "1. Move controllers to api/controller/"
echo "2. Move DTOs to api/dto/request/ and api/dto/response/"
echo "3. Move entities to domain/entity/"
echo "4. Move repositories to domain/repository/"
echo "5. Move service implementations to domain/service/impl/"
echo "6. Create service interfaces in domain/service/"
echo "6. Move security/config files to config/"
echo "7. Move client adapters to infrastructure/client/"
echo "8. Move ratelimit to infrastructure/ratelimit/"
echo "9. Move test files to unit/ and integration/"
echo "10. Update package declarations in all moved files"
echo "11. Create test support files in support/"