# Bhukkad System Architecture

This document provides an overview of the Bhukkad Food Delivery System architecture, including module organization, technology stack, and design patterns.

## System Overview

Bhukkad is built as a distributed Spring Boot 3.2 microservices system. Each domain (identity, restaurant, order, payment, delivery, notification, admin-analytics) runs as an independently deployable service with its own PostgreSQL database. An API Gateway (Spring Cloud Gateway) routes traffic by path to the appropriate service. The architecture follows clean separation of concerns with controller → service → repository layers within each service.

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.0 |
| API Gateway | Spring Cloud Gateway | latest |
| Database | PostgreSQL | 16 (per-service) |
| Cache | Redis | 7 |
| Messaging | Redpanda / Kafka | latest |
| Security | Spring Security + JWT | 6.2.0 |
| API Docs | SpringDoc OpenAPI | 2.2.0 |
| Build | Maven | 3.9.x |
| Container | Docker | Latest |
| Monitoring | Micrometer + Prometheus | 1.12.0 |
| Tracing | OpenTelemetry + Zipkin | latest |

## Service Organization

```
services/
├── pom.xml                  # aggregator (parent: spring-boot-starter-parent BOM)
├── platform-lib/            # shared infrastructure (no web, no datasource)
│   ├── event/               # PlatformEventMessage envelope
│   ├── tracing/             # TraceContext + W3C traceparent
│   ├── error/               # ApiError + BusinessException hierarchy
│   ├── outbox/              # OutboxEvent entity/repo + OutboxClient
│   ├── idempotency/         # IdempotencyRecord entity/repo
│   ├── saga/                # SagaInstance/SagaStep + SagaCoordinator
│   └── src/main/resources/db/migration-pg/   # canonical platform PG baseline
├── gateway/                 # Spring Cloud Gateway (edge routing)
├── identity/                # auth, users, JWT validation
├── restaurant/              # restaurants, menu, cuisines, reviews
├── order/                   # orders, cart, coupons, disputes
├── payment/                 # payments, wallet
├── delivery/                # delivery tracking, serviceability
├── notification/            # async notifications (event consumer)
└── admin-analytics/         # read-only analytics projections
```

## Architecture Layers (per service)

### 1. Controller Layer
- Handles HTTP requests/responses
- Validates input using Jakarta Validation
- Maps requests to service calls
- Returns appropriate HTTP status codes

### 2. Service Layer
- Contains business logic
- Orchestrates repository calls
- Manages transactions
- Enforces business rules

### 3. Repository Layer
- Data access abstraction
- Custom query methods
- Caching integration
- No business logic

### 4. Entity Layer
- JPA entities with relationships
- Audit fields (createdAt, updatedAt)
- Optimistic locking for concurrency

## Key Design Patterns

### DTO Pattern
All API requests and responses use dedicated DTOs, never exposing JPA entities directly.

### Service Pattern
Business logic is organized by domain within each service.

### Repository Pattern
Data access is abstracted behind repository interfaces.

### Cache-Aside Pattern
- Read: Check cache first, fall back to DB, update cache
- Write: Update DB first, invalidate cache
- TTL-based expiration for eventual consistency

## Security Architecture

### Authentication
- JWT-based stateless authentication
- Access tokens + Refresh tokens
- Bcrypt password hashing

### Authorization
- Role-based access control (RBAC)
- Four roles: CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT, ADMIN

### API Security
- Rate limiting per endpoint at the gateway
- CORS configuration
- Security headers (CSP, HSTS, X-Frame-Options)

## Data Architecture

### Database
- Per-service PostgreSQL databases (e.g. `bhukkad_orders`, `bhukkad_restaurants`)
- Connection pooling: HikariCP
- Migrations: Flyway (versioned SQL scripts per service)

### Caching Strategy
- Redis for session, rate limit, and data caching
- Cache keys prefixed with `bhukkad:`
- TTL varies by data type

### Event-Driven Integration
- Outbox pattern for reliable event publishing
- Redpanda/Kafka for async inter-service communication
- Saga orchestration for distributed transactions

## Scalability Considerations

### Horizontal Scaling
- Stateless application servers (no session affinity)
- Shared Redis for session and cache
- Per-service HPA in Kubernetes

### Performance Optimizations
- Batch operations for bulk inserts/updates
- Query optimization (fetch joins, pagination)
- Async processing for non-critical operations

## Monitoring & Observability

### Metrics
- Micrometer + Prometheus for metrics
- Custom business metrics
- JVM metrics (GC, memory, threads)

### Logging
- Structured JSON logging via Logstash encoder
- Request/response logging with trace IDs

### Tracing
- OpenTelemetry distributed tracing
- Trace ID propagated across services
- Span annotations for key operations

## Deployment Architecture

### Kubernetes
- One Deployment per service
- Per-service HPA + PDB
- Gateway + ingress for external traffic
- Blue-green rollout support via k8s/ overlays

### Production
- 3+ gateway pods, 2-3 pods per service
- Load balancer with health checks
- Auto-scaling based on CPU + custom metrics
- Rolling update strategy

---

## Getting Started

1. **Prerequisites**: Java 17, Docker, kubectl, minikube (for local)
2. **Build all services**: `./mvnw -f services/pom.xml verify`
3. **Run locally**: `docker compose -f docker/docker-compose.dev.yml up`
4. **Deploy to k8s**: `bash k8s/scripts/deploy.sh`
5. **Test**: `./mvnw -f services/pom.xml test`

For detailed setup instructions, see [operations documentation](OPERATIONS.md).
