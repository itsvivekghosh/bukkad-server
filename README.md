# 🍔 Bhukkad - Food Delivery Platform

A comprehensive food delivery platform built with Spring Boot microservices, similar to Swiggy and Zomato.

![Java](https://img.shields.io/badge/Java-17-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.12-green)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue)
![Redis](https://img.shields.io/badge/Redis-7.0-red)
![License](https://img.shields.io/badge/License-MIT-yellow)

> **Architecture:** 17 independent microservices following the strangler-fig pattern. See **[docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)** for details.

> **Documentation:** Full onboarding and runbooks live in **[docs/](./docs/README.md)**.

## 📋 Table of Contents

- [Documentation](./docs/README.md)
- [Services](#-services)
- [Architecture](#-architecture)
- [Quick Start](#-quick-start)
- [Development](#-development)
- [Testing](#-testing)
- [Contributing](#-contributing)

## � microservices

| Service | Port | Description |
|---------|------|-------------|
| gateway | 8080 | API Gateway (Spring Cloud Gateway) |
| identity | 8081 | User authentication & authorization |
| restaurant | 8082 | Restaurant & menu management |
| order | 8083 | Order processing |
| payment | 8084 | Payment handling & settlement |
| delivery | 8085 | Delivery agent management |
| notification | 8086 | Email/SMS push notifications |
| search | 8087 | Restaurant & menu search |
| admin-analytics | 8088 | Admin dashboard & analytics |
| survey | 8089 | Customer feedback surveys |
| referral | 8090 | Referral & loyalty program |
| supportticket | 8091 | Customer support tickets |
| realtime | 8092 | Real-time order tracking (SSE/WebSocket) |
| personalization | 8093 | Recommendations engine |
| growth | 8094 | Campaigns & promotions |
| platform-lib | - | Shared utilities (not a running service) |

## 🏗 Architecture

The platform follows a **microservices architecture** with:

- **API Gateway**: Single entry point routing to per-service Kubernetes DNS names
- **Service Communication**: HTTP REST + gRPC (for high-throughput paths)
- **Database**: PostgreSQL per service with Flyway migrations
- **Caching**: Redis for sessions, rate limiting, and热点 data
- **Security**: JWT-based authentication with role-based access control
- **Observability**: Structured JSON logging with request correlation IDs

See **[docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)** for detailed architecture documentation.

## 🚀 Quick Start

### Prerequisites

- Java 17+
- Maven 3.9+
- Docker & Docker Compose
- PostgreSQL 15+
- Redis 7+

### Build All Services

```bash
cd services
mvn clean install
```

### Run Individual Services

```bash
# Run with Spring Boot
mvn spring-boot:run

# Or use Docker Compose
docker-compose up -d
```

### Run Tests

```bash
# All services
mvn test -f services/pom.xml

# Individual service
mvn test -f services/<service-name>/pom.xml
```

## 🛠 Development

### Adding a New Service

1. Create directory in `services/`
2. Add module entry to `services/pom.xml`
3. Follow existing service structure:
   - `src/main/java/com/bhukkad/<service>/`
   - `src/main/resources/application.yml`
   - `src/test/java/com/bhukkad/<service>/`
   - `src/main/resources/db/migration/` for Flyway migrations

### Database Migrations

Each service manages its own migrations:

```bash
mvn flyway:migrate -f services/<service-name>/pom.xml
```

## 📈 Testing

```bash
# Unit tests
mvn test -f services/pom.xml

# Integration tests (requires Docker for Testcontainers)
mvn verify -f services/pom.xml

# Code coverage report
mvn test jacoco:report -f services/pom.xml
```

## 📝 Documentation

- **[API Documentation](./docs/DEVELOPER_API.md)**
- **[Architecture](./docs/ARCHITECTURE.md)**
- **[Operations](./docs/OPERATIONS.md)**
- **[Migration Guide](./docs/migration-guide.md)**

## 📄 License

MIT License
