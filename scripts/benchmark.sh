#!/bin/bash
# ==============================================================================
# Bhukkad Backend - Performance Benchmark Script
#
# Runs benchmarking tests against critical API endpoints to validate
# performance improvements and identify bottlenecks.
#
# Prerequisites:
#   - Apache Bench (ab) installed
#   - wrk or hey for advanced load testing (optional)
#   - Application running with test data
#
# Usage:
#   ./scripts/benchmark.sh [concurrent-users] [total-requests]
#
# Defaults: 10 concurrent users, 1000 total requests per endpoint
# ==============================================================================

set -euo pipefail

# Configuration
API_URL="${API_URL:-http://localhost:8080}"
CONCURRENT_USERS="${1:-10}"
TOTAL_REQUESTS="${2:-1000}"
TEST_TOKEN="${TEST_TOKEN:-test-jwt-token}"

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Bhukkad Performance Benchmark${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Configuration:"
echo "  API URL:        ${API_URL}"
echo "  Concurrent:     ${CONCURRENT_USERS} users"
echo "  Total Requests: ${TOTAL_REQUESTS}"
echo ""

# Check if ab is available
if ! command -v ab &> /dev/null; then
    echo -e "${RED}ERROR: Apache Bench (ab) is not installed${NC}"
    echo "Install with: apt-get install apache2-utils  # or brew install httpd"
    exit 1
fi

BENCHMARK_ENDPOINT="/api/v1/orders/customer/my-orders"

echo -e "${YELLOW}→ Testing Order List Endpoint${NC}"
echo "  Endpoint: GET ${BENCHMARK_ENDPOINT}"
ab -n ${TOTAL_REQUESTS} -c ${CONCURRENT_USERS} -H "Authorization: Bearer ${TEST_TOKEN}" "${API_URL}${BENCHMARK_ENDPOINT}" 2>&1

echo ""
echo -e "${YELLOW}→ Testing Order Track Endpoint${NC}"
echo "  Endpoint: GET ${BENCHMARK_ENDPOINT}/cursor"
ab -n ${TOTAL_REQUESTS} -c ${CONCURRENT_USERS} -H "Authorization: Bearer ${TEST_TOKEN}" "${API_URL}${BENCHMARK_ENDPOINT}/cursor" 2>&1

echo ""
echo -e "${YELLOW}→ Testing Restaurant Orders Endpoint${NC}"
echo "  Endpoint: GET /api/v1/orders/restaurant/1"
ab -n ${TOTAL_REQUESTS} -c ${CONCURRENT_USERS} -H "Authorization: Bearer ${TEST_TOKEN}" "${API_URL}/api/v1/orders/restaurant/1" 2>&1

echo ""
echo -e "${YELLOW}→ Testing Restaurant Kitchen Queue Endpoint${NC}"
echo "  Endpoint: GET /api/v1/orders/restaurant/1/kitchen-queue"
ab -n ${TOTAL_REQUESTS} -c ${CONCURRENT_USERS} -H "Authorization: Bearer ${TEST_TOKEN}" "${API_URL}/api/v1/orders/restaurant/1/kitchen-queue" 2>&1

echo ""
echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}Benchmark Summary${NC}"
echo -e "${BLUE}========================================${NC}"

echo ""
echo "Performance Improvements to validate:"
echo "  1. Read replica usage for GET endpoints"
echo "  2. Redis caching for order list and kitchen queue"
echo "  3. Idempotency key caching for order creation"
echo "  4. Fraud detection query performance"
echo "  5. Payment gateway circuit breaker fallback behavior"
echo ""
echo "Expected response times:"
echo "  - Order list: <500ms (cached after first request)"
echo "  - Kitchen queue: <200ms (Redis cached)"
echo "  - Order track: <300ms (Redis cached)"
echo "  - Order creation: <2s (async path)"
echo ""
echo -e "${GREEN}Benchmark complete!${NC}"
