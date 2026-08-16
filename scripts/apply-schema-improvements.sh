#!/bin/bash
# ==============================================================================
# Bhukkad Backend - Schema & Index Optimization Script
# 
# This script applies critical database index improvements to support the
# fraud detection, rate limiting, and performance optimizations.
#
# Prerequisites:
#   - MySQL/MariaDB connection details in environment variables
#   - Database user with ALTER privileges
#
# Usage:
#   ./scripts/apply-schema-improvements.sh [--dry-run]
#
# Environment Variables:
#   DB_HOST: Database host (default: localhost)
#   DB_PORT: Database port (default: 3306)
#   DB_NAME: Database name (default: bhukkad)
#   DB_USER: Database user
#   DB_PASSWORD: Database password
# ==============================================================================

set -euo pipefail

# Configuration
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-bhukkad}"
DB_USER="${DB_USER:-bhukkad}"
DB_PASSWORD="${DB_PASSWORD:-}"
DRY_RUN="${1:-}"

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Bhukkad Schema Optimization Script${NC}"
echo -e "${BLUE}========================================${NC}"

if [[ "$DRY_RUN" == "--dry-run" ]]; then
    echo -e "${YELLOW}[DRY RUN MODE] - No changes will be applied${NC}"
fi

# Build MySQL command
MYSQL_CMD="mysql -h${DB_HOST} -P${DB_PORT} -u${DB_USER} -p${DB_PASSWORD} ${DB_NAME}"
if [[ "$DRY_RUN" == "--dry-run" ]]; then
    MYSQL_CMD="echo '[DRY RUN]' $MYSQL_CMD"
fi

# Function to execute SQL and report
execute_sql() {
    local description="$1"
    local sql="$2"
    echo -e "${YELLOW}→ $description${NC}"
    if eval "$MYSQL_CMD -e \"$sql\""; then
        echo -e "${GREEN}✓ Success${NC}"
    else
        echo -e "${RED}✗ Failed${NC}"
        return 1
    fi
}

# 1. Enhanced Fraud Event Indexes
echo -e "\n${BLUE}1. Fraud Event Table Optimizations${NC}"
echo "-----------------------------------"

execute_sql "Add composite index for fraud IP lookup" \
    "CREATE INDEX IF NOT EXISTS idx_fraud_ip_type_created ON fraud_events (event_type, ip_address, created_at);"

execute_sql "Add composite index for fraud fingerprint lookup" \
    "CREATE INDEX IF NOT EXISTS idx_fraud_fp_type_created ON fraud_events (event_type, device_fingerprint, created_at);"

execute_sql "Add index for fraud admin dashboard" \
    "CREATE INDEX IF NOT EXISTS idx_fraud_created_type ON fraud_events (created_at, event_type);"

# 2. Order Table Enhancements
echo -e "\n${BLUE}2. Order Table Optimizations${NC}"
echo "-----------------------------------"

execute_sql "Add index for customer order lookups" \
    "CREATE INDEX IF NOT EXISTS idx_orders_customer_created ON orders (customer_id, created_at);"

execute_sql "Add index for restaurant order lookups" \
    "CREATE INDEX IF NOT EXISTS idx_orders_restaurant_status_created ON orders (restaurant_id, status, created_at);"

execute_sql "Add index for delivery agent order lookups" \
    "CREATE INDEX IF NOT EXISTS idx_orders_delivery_agent_status ON orders (delivery_agent_id, status);"

execute_sql "Add index for order number lookups" \
    "CREATE INDEX IF NOT EXISTS idx_orders_number ON orders (order_number);"

execute_sql "Add index for scheduled order dispatch" \
    "CREATE INDEX IF NOT EXISTS idx_orders_scheduled_at ON orders (scheduled_at) WHERE status = 'SCHEDULED';"

# 3. Payment Table Enhancements
echo -e "\n${BLUE}3. Payment Table Optimizations${NC}"
echo "-----------------------------------"

execute_sql "Add index for payment status lookups" \
    "CREATE INDEX IF NOT EXISTS idx_payments_status_created ON payments (status, created_at);"

execute_sql "Add index for idempotency key lookups" \
    "CREATE INDEX IF NOT EXISTS idx_payments_idempotency_key ON payments (idempotency_key) WHERE idempotency_key IS NOT NULL;"

# 4. Notification Preferences Optimization
echo -e "\n${BLUE}4. Notification Preferences Optimizations${NC}"
echo "-----------------------------------"

execute_sql "Add composite index for notification preference lookups" \
    "CREATE INDEX IF NOT EXISTS idx_notif_prefs_customer ON customer_notification_preferences (customer_id);"

# 5. Idempotency Record Cleanup
echo -e "\n${BLUE}5. Idempotency Record Optimizations${NC}"
echo "-----------------------------------"

execute_sql "Add index for idempotency record cleanup" \
    "CREATE INDEX IF NOT EXISTS idx_idempotency_expires ON idempotency_records (expires_at);"

execute_sql "Add unique constraint to prevent race conditions" \
    "CREATE UNIQUE INDEX IF NOT EXISTS uk_idempotency_scope_key ON idempotency_records (scope, idempotency_key);"

# 6. Cache Warm-up Strategy Tables
echo -e "\n${BLUE}6. Cache Warm-up Optimizations${NC}"
echo "-----------------------------------"

execute_sql "Add index for order status change tracking" \
    "CREATE INDEX IF NOT EXISTS idx_orders_updated_at ON orders (updated_at DESC);"

# Summary
echo -e "\n${GREEN}========================================${NC}"
echo -e "${GREEN}Schema optimization complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Indexes added:"
echo "  - Fraud events: 3 indexes for IP/device/type lookups"
echo "  - Orders: 5 indexes for customer/restaurant/agent queries"
echo "  - Payments: 2 indexes for status/idempotency"
echo "  - Notification prefs: 1 index for customer lookup"
echo "  - Idempotency records: 2 indexes for cleanup and uniqueness"
echo "  - Order status: 1 index for cache invalidation"
echo ""
if [[ "$DRY_RUN" != "--dry-run" ]]; then
    echo -e "${BLUE}Run ANALYZE TABLE to update index statistics:${NC}"
    echo "  ANALYZE TABLE fraud_events, orders, payments, idempotency_records;"
fi
echo ""
    echo -e "${YELLOW}Next steps:${NC}"
echo "  1. Run application with updated configuration"
echo "  2. Monitor query performance in slow log"
echo "  3. Run EXPLAIN on fraud detection counting queries"
echo "  4. Verify index usage with performance_schema"
