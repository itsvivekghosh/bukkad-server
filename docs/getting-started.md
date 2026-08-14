# Getting Started

This guide gets you from zero to a running Bhukkad API on your machine.

## What is Bhukkad?

Bhukkad is a Spring Boot 3.2 food-delivery backend (similar to Swiggy/Zomato) with:

- JWT authentication and role-based access (`CUSTOMER`, `RESTAURANT_OWNER`, `DELIVERY_AGENT`, `ADMIN`)
- MySQL + Flyway migrations, Redis caching
- Orders, cart, payments (simulated + Razorpay), wallet, loyalty
- Real-time order updates via SSE and optional WebSocket/STOMP
- Prometheus metrics, rate limiting, read-replica routing

## Prerequisites

| Tool | Version | Required for |
|------|---------|--------------|
| Java JDK | 17 | Local Maven run, tests |
| Maven | 3.9+ | Build & test |
| MySQL | 8.0 | Database (or use Docker) |
| Redis | 7.x | Cache (or use Docker) |
| Docker + Compose | 24+ | Recommended local stack |
| kubectl + minikube | Optional | Kubernetes local testing |

Verify:

```bash
java -version    # openjdk 17
mvn -version
docker compose version
```

## Repository layout

```
backend-server/
├── src/main/java/com/bhukkad/   # Application code
│   ├── controller/              # REST API (all use /api/v1)
│   ├── service/                 # Business logic interfaces
│   ├── serviceImpl/             # Service implementations
│   ├── config/                  # Security, Redis, async, etc.
│   ├── entity/                  # JPA entities
│   └── repository/              # Spring Data JPA
├── src/main/resources/
│   ├── application.yml          # Base config
│   ├── application-dev.yml      # Dev profile
│   ├── application-prod.yml     # Prod profile
│   └── db/migration/            # Flyway SQL (V1–V4)
├── src/test/                    # Unit & integration tests
├── docker/                      # Dockerfile, compose, scripts
├── k8s/                         # Kubernetes manifests (Kustomize)
└── docs/                        # This documentation
```

## Fastest path: Docker (recommended)

From the repository root:

```bash
# Start MySQL, Redis, and the API
./docker/scripts/deploy.sh

# Optional: include Redis Commander + phpMyAdmin
./docker/scripts/deploy.sh   # tools are started by default in dev script
# Skip UI tools:
./docker/scripts/deploy.sh --no-tools
```

Verify:

```bash
curl http://localhost:8080/api/v1/health/ping
# {"status":"pong",...}
```

Open Swagger: [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)

## Alternative: IDE + local services

See [local-development.md](./local-development.md) if you prefer running the JAR directly without Docker.

## First API calls

### 1. Register a customer

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Test User",
    "email": "customer@example.com",
    "password": "secret123",
    "phoneNumber": "9876543210",
    "role": "CUSTOMER"
  }' | jq .
```

Save the `accessToken` from the response.

### 2. Authenticated request

```bash
export TOKEN="<accessToken from register/login>"

curl -s http://localhost:8080/api/v1/customers/profile \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### 3. Browse restaurants (public)

```bash
curl -s http://localhost:8080/api/v1/restaurants/public | jq .
```

Full API walkthrough: [api-usage.md](./api-usage.md).

## Run tests

```bash
mvn clean test
```

Expect **700+** unit tests. First run downloads Maven dependencies.

## User roles

Register with the `role` field in `/auth/register`:

| Role | Purpose |
|------|---------|
| `CUSTOMER` | Browse, cart, orders, wallet |
| `RESTAURANT_OWNER` | Manage restaurants & menus, kitchen queue |
| `DELIVERY_AGENT` | Accept deliveries, earnings |
| `ADMIN` | Platform admin, cache, analytics |

## Next steps

| Goal | Document |
|------|----------|
| Configure env vars & secrets | [configuration.md](./configuration.md) |
| Docker prod stack | [docker.md](./docker.md) |
| Deploy to Kubernetes | [kubernetes.md](./kubernetes.md) |
| Order/cart/payment flows | [api-usage.md](./api-usage.md) |
| Logs, metrics, debugging | [operations.md](./operations.md) |
