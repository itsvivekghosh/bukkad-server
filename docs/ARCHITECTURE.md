# Bhukkad System Architecture

This document provides an overview of the Bhukkad Food Delivery System architecture, including module organization, technology stack, and design patterns.

## System Overview

Bhukkad is built as a Spring Boot 3.2 monolith with clear module boundaries, designed for high traffic (5k+ RPS) with horizontal scaling in mind. The architecture follows clean separation of concerns with controller → service → repository layers.

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Java | 17 |
| Framework | Spring Boot | 3.2.0 |
| Database | MySQL | 8.0 |
| Cache | Redis | 7 |
| Security | Spring Security + JWT | 6.2.0 |
| API Docs | SpringDoc OpenAPI | 2.2.0 |
| Build | Maven | 3.9.x |
| Container | Docker | Latest |
| Monitoring | Micrometer + Prometheus | 1.12.0 |

## Module Organization

```
backend-server/
├── src/main/java/com/bhukkad/
│   ├── controller/          # REST controllers (API layer)
│   ├── service/             # Business logic interfaces
│   ├── serviceImpl/         # Business logic implementations
│   ├── repository/          # Data access layer
│   ├── entity/              # JPA entities
│   ├── dto/                 # Data Transfer Objects
│   │   ├── request/         # Request DTOs
│   │   └── response/        # Response DTOs
│   ├── config/              # Configuration classes
│   ├── security/            # Authentication & authorization
│   ├── exception/           # Custom exceptions & handlers
│   ├── cache/               # Caching strategies
│   ├── logging/             # Request logging & alerting
│   └── ...
├── src/main/resources/
│   ├── db/migration/        # Flyway SQL migrations
│   ├── application.yml      # Base configuration
│   ├── application-dev.yml  # Development profile
│   └── application-prod.yml # Production profile
├── docker/                  # Docker configuration
├── scripts/                 # Utility scripts
└── docs/                    # Documentation
```

## Architecture Layers

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
All API requests and responses use dedicated DTOs, never exposing JPA entities directly. This prevents:
- Over-fetching/under-fetching data
- Accidental data exposure
- Circular serialization issues

### Service Pattern
Business logic is organized by domain:
- `AuthService` / `AuthServiceImpl` — Authentication
- `OrderService` / `OrderServiceImpl` — Order management
- `CartService` / `CartServiceImpl` — Cart operations

### Repository Pattern
Data access is abstracted behind repository interfaces:
- `JpaRepository` for basic CRUD
- Custom query methods for complex queries
- `@Query` annotations for native/HQL queries

### Cache-Aside Pattern
- Read: Check cache first, fall back to DB, update cache
- Write: Update DB first, invalidate cache
- TTL-based expiration for eventual consistency

## Security Architecture

### Authentication
- JWT-based stateless authentication
- Access tokens (24h) + Refresh tokens (7 days)
- Bcrypt password hashing (strength 12)

### Authorization
- Role-based access control (RBAC)
- `@PreAuthorize` annotations on endpoints
- Four roles: CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT, ADMIN

### API Security
- Rate limiting per endpoint
- WAF (Web Application Firewall) for common attacks
- CORS configuration
- Security headers (CSP, HSTS, X-Frame-Options)

## Data Architecture

### Database
- Primary: MySQL 8.0 (single schema `bhukkad`)
- Connection pooling: HikariCP (30 connections per pod)
- Migrations: Flyway (versioned SQL scripts)

### Caching Strategy
- Redis for session, rate limit, and data caching
- Cache keys prefixed with `bhukkad:`
- TTL varies by data type (5min - 24hrs)
- Distributed cache invalidation via Redis pub/sub

### Read/Write Separation
- Read replicas for read-heavy endpoints
- Write-through to primary for mutations
- Connection routing based on operation type

## Scalability Considerations

### Horizontal Scaling
- Stateless application servers (no session affinity)
- Shared Redis for session and cache
- Database connection pool sized per pod

### Performance Optimizations
- Batch operations for bulk inserts/updates
- Query optimization (fetch joins, pagination)
- Connection pooling with leak detection
- Async processing for non-critical operations

### Caching Layers
- L1: In-memory (Caffeine) for hot data
- L2: Redis for shared cache across pods
- CDN for static assets (images, etc.)

## Monitoring & Observability

### Metrics
- Micrometer + Prometheus for metrics
- Custom business metrics (order rate, revenue)
- JVM metrics (GC, memory, threads)

### Logging
- Structured JSON logging via Logstash encoder
- Request/response logging with trace IDs
- Performance metrics for slow requests

### Tracing
- OpenTelemetry distributed tracing
- Trace ID propagated across services
- Span annotations for key operations

## Deployment Architecture

### Docker
- Multi-stage Dockerfile for optimized image size
- Non-root user for security
- Health checks for readiness probes

### Production
- 10 pods recommended for 5k RPS
- Load balancer with health checks
- Auto-scaling based on CPU/memory
- Blue-green deployment strategy

---

## Getting Started

1. **Prerequisites**: Java 17, MySQL 8.0, Redis 7
2. **Build**: `./mvnw clean package`
3. **Run**: `java -jar target/bhukkad-delivery-system-1.0.0.jar`
4. **Test**: `python scripts/test-all-apis.py`

For detailed setup instructions, see [operations documentation](OPERATIONS.md).
