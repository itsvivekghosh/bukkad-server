# Bhukkad Microservices - Standard Service Architecture

## Overview
This document defines the standard folder architecture for all Bhukkad microservices. The architecture follows clean architecture principles with clear separation of concerns.

## Standard Directory Structure

### Main Source (`src/main/java/com/bhukkad/<service>/`)

```
<service>/
├── <Service>Application.java           # Spring Boot entry point
├── config/                             # Configuration classes
│   ├── <Service>Config.java
│   ├── SecurityConfig.java
│   └── <Feature>Config.java
├── api/                                # REST API layer (controllers & DTOs)
│   ├── controller/                     # REST controllers
│   │   └── *Controller.java
│   ├── dto/                            # Data Transfer Objects
│   │   ├── request/                    # Request DTOs
│   │   │   └── *Request.java
│   │   ├── response/                   # Response DTOs
│   │   │   └── *Response.java
│   │   └── *.java                      # Other DTOs (ports, etc.)
│   └── exception/                      # Exception handlers
│       └── <Service>ExceptionHandler.java
├── domain/                             # Domain layer (core business logic)
│   ├── entity/                         # JPA entities
│   │   └── *.java
│   ├── repository/                     # Repository interfaces
│   │   └── *Repository.java
│   ├── service/                        # Service interfaces
│   │   └── *Service.java
│   ├── service/impl/                   # Service implementations
│   │   └── *ServiceImpl.java
│   ├── event/                          # Domain events
│   │   └── *.java
│   └── mapper/                         # MapStruct mappers
│       └── *Mapper.java
├── infrastructure/                     # Infrastructure adapters
│   ├── client/                         # External service clients
│   │   └── *Client.java
│   ├── persistence/                    # Persistence implementations
│   │   └── *RepositoryImpl.java
│   ├── messaging/                      # Message consumers/publishers
│   │   ├── *Consumer.java
│   │   └── *Publisher.java
│   ├── cache/                          # Cache services
│   │   └── *CacheService.java
│   └── ratelimit/                      # Rate limiting
│       └── *.java
└── util/                               # Utility classes
    └── *.java
```

### Test Source (`src/test/java/com/bhukkad/<service>/`)

```
<service>/
├── unit/                               # Unit tests (fast, no external deps)
│   ├── service/                        # Service layer tests
│   │   └── *ServiceTest.java
│   ├── controller/                     # Controller slice tests
│   │   └── *ControllerTest.java
│   └── domain/                         # Domain/entity tests
│       └── *Test.java
├── integration/                        # Integration tests (with Testcontainers)
│   ├── controller/                     # Controller integration tests
│   │   └── *ControllerIntegrationTest.java
│   ├── repository/                     # Repository integration tests
│   │   └── *RepositoryIntegrationTest.java
│   └── messaging/                      # Messaging integration tests
│       └── *ConsumerIntegrationTest.java
├── architecture/                       # Architecture tests (ArchUnit)
│   └── <Service>ArchTest.java
└── support/                            # Test support classes
    ├── <Service>IntegrationTestBase.java
    └── <Service>TestDataBuilder.java
```

## Package Naming Conventions

| Layer | Package Suffix | Example |
|-------|---------------|---------|
| Application | (root) | `com.bhukkad.identity` |
| Config | `.config` | `com.bhukkad.identity.config` |
| API Controllers | `.api.controller` | `com.bhukkad.identity.api.controller` |
| API DTOs Request | `.api.dto.request` | `com.bhukkad.identity.api.dto.request` |
| API DTOs Response | `.api.dto.response` | `com.bhukkad.identity.api.dto.response` |
| API Exceptions | `.api.exception` | `com.bhukkad.identity.api.exception` |
| Entities | `.domain.entity` | `com.bhukkad.identity.domain.entity` |
| Repositories | `.domain.repository` | `com.bhukkad.identity.domain.repository` |
| Service Interfaces | `.domain.service` | `com.bhukkad.identity.domain.service` |
| Service Implementations | `.domain.service.impl` | `com.bhukkad.identity.domain.service.impl` |
| Domain Events | `.domain.event` | `com.bhukkad.identity.domain.event` |
| Mappers | `.domain.mapper` | `com.bhukkad.identity.domain.mapper` |
| Clients | `.infrastructure.client` | `com.bhukkad.identity.infrastructure.client` |
| Persistence | `.infrastructure.persistence` | `com.bhukkad.identity.infrastructure.persistence` |
| Messaging | `.infrastructure.messaging` | `com.bhukkad.identity.infrastructure.messaging` |
| Cache | `.infrastructure.cache` | `com.bhukkad.identity.infrastructure.cache` |
| Rate Limiting | `.infrastructure.ratelimit` | `com.bhukkad.identity.infrastructure.ratelimit` |
| Utilities | `.util` | `com.bhukkad.identity.util` |

## Key Principles

### 1. Dependency Direction
```
api → domain.service → domain.entity
infrastructure → domain (implements interfaces)
```

- **api** depends on **domain.service** (interfaces)
- **domain.service.impl** implements **domain.service** interfaces
- **infrastructure** implements **domain** interfaces (repositories, clients)
- **domain** has NO dependencies on outer layers

### 2. DTO Pattern
- Never expose JPA entities directly via API
- Use dedicated request/response DTOs
- Map between DTOs and entities using MapStruct mappers

### 3. Service Pattern
- Define service contracts as interfaces in `domain.service`
- Implement in `domain.service.impl`
- Controllers depend on interfaces, not implementations

### 4. Repository Pattern
- Repository interfaces in `domain.repository`
- Implementations (if custom) in `infrastructure.persistence`
- Spring Data JPA provides default implementations

### 5. Configuration
- All `@Configuration` classes in `config/`
- Feature-specific configs (SecurityConfig, CacheConfig, etc.)

### 6. Testing Strategy
- **Unit tests**: Test single classes with mocked dependencies
- **Integration tests**: Test with real DB (Testcontainers), Kafka, Redis
- **Architecture tests**: Enforce package dependencies with ArchUnit
- **Test support**: Shared test base classes and data builders

## Service-Specific Variations

Some services may have additional packages based on their domain:

| Service | Additional Packages |
|---------|---------------------|
| restaurant | `.domain.service.inventory`, `.domain.service.promotion` |
| order | `.infrastructure.client` (for restaurant/payment clients) |
| payment | `.domain.gateway` (PSP adapters) |
| realtime | `.api.sse`, `.infrastructure.messaging.sse` |
| delivery | `.domain.service.matching`, `.domain.service.tracking` |

## Migration Checklist

When restructuring a service:

- [ ] Create standard directory structure (use `scripts/restructure-service.sh`)
- [ ] Move controllers to `api/controller/`
- [ ] Move request DTOs to `api/dto/request/`
- [ ] Move response DTOs to `api/dto/response/`
- [ ] Move entities to `domain/entity/`
- [ ] Move repositories to `domain/repository/`
- [ ] Move service implementations to `domain/service/impl/`
- [ ] Create service interfaces in `domain/service/`
- [ ] Move security/config to `config/`
- [ ] Move clients to `infrastructure/client/`
- [ ] Move ratelimit to `infrastructure/ratelimit/`
- [ ] Move test files to `unit/` and `integration/`
- [ ] Update all package declarations
- [ ] Update all import statements
- [ ] Create test support files in `support/`
- [ ] Run `./mvnw -f services/pom.xml test -pl <service>` to verify

## Shared Test Infrastructure

The `test-support` module provides:
- `TestcontainersConfiguration` - Shared PG/Kafka/Redis containers
- `TestDataBuilder` - Base class for test data generation
- `BaseIntegrationTest` - Base class for integration tests with MockMvc
- Test slice annotations: `@ControllerTest`, `@ServiceTest`, `@RepositoryTest`

Add to service pom.xml:
```xml
<dependency>
    <groupId>com.bhukkad</groupId>
    <artifactId>test-support</artifactId>
    <version>${project.version}</version>
    <scope>test</scope>
</dependency>
```