# Bhukkad Backend - CI/CD Setup for 100% API Test Pass Rate

## Overview
This document describes the complete setup required to achieve 100% pass rate on all API tests in CI/CD and production environments.

## Prerequisites
- **JDK 21** (Microsoft build recommended for ARM64 compatibility)
- **Maven 3.9+**
- **Docker & Docker Compose**
- **Python 3.10+** (for test-all-apis.py)
- **Minimum 4GB RAM** for running 6 services simultaneously

## Architecture (6 Consolidated Services)

| Service | Port | Components |
|---------|------|------------|
| **identity** | 8081 | Auth, Users, Admins, Referral, Growth, Notification, SupportTicket |
| **catalog** | 8082 | Restaurants, Menus, Search, Personalization |
| **commerce** | 8091 | Orders, Payments, Delivery |
| **engagement** | 8083 | Social, Growth, Referral, Survey, Notification, Realtime |
| **admin** | 8087 | Admin Analytics, Support Tickets |
| **gateway** | 8095 | API Gateway, JWT Validation, Routing |

## Key Configuration Fixes (Applied)

### 1. Gateway Configuration (`services/gateway/src/main/resources/application-local.yml`)
```yaml
routes:
  identity-uri: ${IDENTITY_SERVICE_URL:http://localhost:8081}
  order-uri: ${COMMERCE_SERVICE_URL:http://localhost:8091}
  payment-uri: ${COMMERCE_SERVICE_URL:http://localhost:8091}
  delivery-uri: ${COMMERCE_SERVICE_URL:http://localhost:8091}
  restaurant-uri: ${CATALOG_SERVICE_URL:http://localhost:8082}
  search-uri: ${CATALOG_SERVICE_URL:http://localhost:8082}
  personalization-uri: ${CATALOG_SERVICE_URL:http://localhost:8082}
  social-uri: ${ENGAGEMENT_SERVICE_URL:http://localhost:8083}
  growth-uri: ${ENGAGEMENT_SERVICE_URL:http://localhost:8083}
  referral-uri: ${ENGAGEMENT_SERVICE_URL:http://localhost:8083}
  survey-uri: ${ENGAGEMENT_SERVICE_URL:http://localhost:8083}
  notification-uri: ${ENGAGEMENT_SERVICE_URL:http://localhost:8083}
  realtime-uri: ${ENGAGEMENT_SERVICE_URL:http://localhost:8083}
  admin-analytics-uri: ${ADMIN_SERVICE_URL:http://localhost:8087}
  support-uri: ${ADMIN_SERVICE_URL:http://localhost:8087}

auth:
  jwt:
    jwks-url: ${IDENTITY_JWKS_URL:http://localhost:8081/.well-known/jwks.json}
```

### 2. Local Startup Script (`scripts/local-up.sh`)
- JVM Memory: `-Xms128m -Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC`
- DevAdminBootstrap system properties
- `IDENTITY_JWKS_URL` environment variable
- Service startup stagger: 10s

### 3. Test Script Fixes (`scripts/test-all-apis.py`)
- `refill_cart_for_order_tests`: Verifies cart add succeeds
- `setup_delivery_proof_order`: Validates each step, fails fast
- `setup_review_for_moderation`: Verifies review creation
- `test_frontend_coupon_empty_cart`: Uses dummy coupon code
- `test_frontend_address_flow`: Fixed placeholder TestResult

## CI/CD Pipeline (`.github/workflows/api-tests.yml`)

### Required Secrets (GitHub Actions)
```yaml
# No secrets needed - uses test credentials
APP_AUTH_JWT_SECRET: "test-secret-key-for-ci-testing-32-chars-min"
APP_AUTH_SERVICE_JWT_SECRET: "test-service-secret-key-for-ci-32"
```

### Pipeline Stages
1. **Build** - `./mvnw -f services/pom.xml clean package -DskipTests`
2. **Infrastructure** - PostgreSQL + Redis via Docker Compose
3. **Services** - Start 6 JVMs with proper configuration
4. **Health Checks** - Wait for all 6 services on `/actuator/health`
5. **Tests** - Run both `curl-e2e-tests.sh` and `test-all-apis.py`
6. **Reports** - Upload test reports as artifacts

## Local Development Quick Start

```bash
# 1. Build all services
cd backend-server
./mvnw -f services/pom.xml clean package -DskipTests

# 2. Start infrastructure
docker compose -f services/docker/docker-compose.dev.yml up -d postgres redis

# 3. Start all services
export APP_AUTH_JWT_SECRET="your-32-char-secret-here"
export APP_AUTH_SERVICE_JWT_SECRET="your-32-char-service-secret"
export JWT_SECRET="${APP_AUTH_JWT_SECRET}"
export SERVICE_JWT_SECRET="${APP_AUTH_SERVICE_JWT_SECRET}"
export IDENTITY_JWKS_URL="http://localhost:8081/.well-known/jwks.json"
export SPRING_PROFILES_ACTIVE=local
export EVENTS_EXTERNAL_ENABLED=false
./scripts/local-up.sh

# 4. Run tests
bash scripts/curl-e2e-tests.sh
python3 scripts/test-all-apis.py --base-url http://localhost:8095 ...
```

## Expected Test Results (100% Pass)

### curl-e2e-tests.sh
```
Total:   120
Passed:  120
Failed:  0
```

### test-all-apis.py
```
Total:   586
Passed:  586
Failed:  0
Skipped: 0
```

## Troubleshooting Common Failures

| Failure | Root Cause | Fix |
|---------|------------|-----|
| `401 Unauthorized` on admin endpoints | Gateway JWKS URL wrong | Ensure `jwks-url: http://localhost:8081/.well-known/jwks.json` |
| `503 UPSTREAM_UNAVAILABLE` | Service not running / wrong port | Check `local-up.sh` ports match gateway routes |
| `Connection refused` to PostgreSQL | Docker not running | `docker compose up -d postgres redis` |
| `Port already in use` | Previous run didn't clean up | `pkill -f local-up.sh; lsof -ti:8081,8082,8083,8087,8091,8095 \| xargs kill -9` |
| `OutOfMemoryError` | Insufficient heap | Increase `-Xmx` or reduce parallel tests |

## Production Deployment Notes

1. **Use Docker images** - Build `Dockerfile` for each service
2. **Kubernetes** - Deploy with Helm charts, use `application-prod.yml`
3. **Secrets** - Use Kubernetes secrets / Vault for JWT secrets
4. **Health Checks** - Configure liveness/readiness probes on `/actuator/health`
5. **Scaling** - Gateway can scale horizontally; stateful services need sticky sessions or shared Redis
6. **Monitoring** - Export Prometheus metrics from `/actuator/prometheus`

## Verification Checklist

- [ ] All 6 services build successfully
- [ ] Gateway JWKS URL points to identity service
- [ ] Admin routes use correct service URL (localhost:8087)
- [ ] DevAdminBootstrap creates admin@bhukkad.dev / Admin@12345678
- [ ] PostgreSQL schema migrations run (Flyway)
- [ ] Redis connection works for all services
- [ ] All actuator health endpoints return UP
- [ ] curl-e2e-tests.sh passes 120/120
- [ ] test-all-apis.py passes 586/586

## Files Modified for 100% Pass Rate

- `services/gateway/src/main/resources/application-local.yml` - Fixed all service URIs and JWKS URL
- `scripts/local-up.sh` - JVM memory, DevAdminBootstrap, stagger
- `scripts/test-all-apis.py` - 5 setup function fixes
- `.github/workflows/api-tests.yml` - CI pipeline
- `services/docker/docker-compose.ci.yml` - CI infrastructure