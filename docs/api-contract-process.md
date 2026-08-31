# Bhukkad API Contract Delivery Process

This document outlines the complete process for defining, reviewing, delivering, and maintaining frontend API contracts in the Bhukkad Food Delivery System. It serves as the single source of truth for API development workflows, ensuring consistency, quality, and alignment between frontend and backend teams.

## Table of Contents

1. [API Contract Definition Process](#1-api-contract-definition-process)
2. [Review and Approval Workflow](#2-review-and-approval-workflow)
3. [Delivery and Deployment Protocol](#3-delivery-and-deployment-protocol)
4. [Contract Lifecycle Management](#4-contract-lifecycle-management)
5. [Technical Implementation Details](#5-technical-implementation-details)
6. [Edge Cases and Error Handling](#6-edge-cases-and-error-handling)
7. [Dependency Management](#7-dependency-management)
8. [API Documentation Integration](#8-api-documentation-integration)
9. [Best Practices and Guidelines](#9-best-practices-and-guidelines)

---

## 1. API Contract Definition Process

### 1.1 Initiation and Requirements Gathering

All API contract changes begin with a clearly defined requirement:

1. **Feature Request Submission**: Product, frontend, or backend teams submit API requirements via Jira tickets with the `API-Contract` label
2. **Requirement Analysis**: Backend lead reviews requirements for feasibility, security implications, and architectural alignment
3. **API Design Session**: Collaborative session between frontend lead, backend lead, and product manager to define:
   - Endpoint purpose and HTTP method
   - Request/response schemas
   - Authentication and authorization requirements
   - Rate limiting and throttling requirements
   - Error conditions and HTTP status codes
   - Idempotency requirements (where applicable)

### 1.2 Contract Specification

API contracts are defined using the following artifacts:

#### 1.2.1 Interface Definition (Primary Source of Truth)
- **Location**: `src/main/java/com/bhukkad/controller/*`
- **Format**: Spring MVC REST controllers with annotations
- **Key Elements**:
  - `@RestController` - Marks class as REST controller
  - `@RequestMapping` - Defines base path (using `ApiPaths.V1_PREFIX`)
  - Method-level annotations (`@GetMapping`, `@PostMapping`, etc.)
  - `@RequestBody` / `@RequestParam` / `@PathVariable` for input
  - `@Valid` for input validation triggering
  - Return type `ResponseEntity<ApiResponse<T>>` for standardized responses
  - `@Operation` (SpringDoc) for OpenAPI documentation
  - `@PreAuthorize` for security constraints
  - Custom annotations like `@RateLimited` for throttling

#### 1.2.2 Request/Response DTOs
- **Request DTOs**: `src/main/java/com/bhukkad/dto/request/*`
- **Response DTOs**: `src/main/java/com/bhukkad/dto/response/*`
- **Validation**: Jakarta Validation annotations (`@NotNull`, `@Size`, `@Pattern`, etc.)
- **Serialization**: Jackson annotations where needed (`@JsonProperty`, `@JsonInclude`)

#### 1.2.3 API Versioning
- All APIs use path-based versioning: `/api/v1/{resource}`
- Version prefix centralized in `ApiPaths.V1_PREFIX`
- Unversioned `/api/*` requests rewritten to v1 by `LegacyApiPathRewriteFilter`
- Future versions will use `/api/v2/{resource}` when breaking changes are required

### 1.3 Contract Documentation

During definition, contracts must be documented in:

1. **Code-Level Documentation**:
   - Javadoc on controller methods explaining purpose and behavior
   - Inline comments for complex logic or non-obvious constraints
   - `@Operation` annotations for OpenAPI/Swagger generation

2. **Architecture Decision Records (ADRs)**:
   - For significant API design decisions impacting multiple teams
   - Stored in `docs/adr/` directory (when created)

3. **API Documentation Updates**:
   - Update relevant files in `docs/api/` directory
   - Ensure OpenAPI/Swagger annotations are complete and accurate

## 2. Review and Approval Workflow

### 2.1 Internal Review

1. **Self-Review Checklist** (Developer):
   - [ ] Contract follows REST principles and HTTP semantics
   - [ ] Proper HTTP status codes used for success/error cases
   - [ ] Input validation applied via Jakarta Validation
   - [ ] Authentication and authorization checks in place
   - [ ] Rate limiting applied where appropriate (`@RateLimited`)
   - [ ] Idempotency keys implemented for state-changing operations
   - [ ] Standard `ApiResponse<T>` wrapper used consistently
   - [ ] Proper error handling with meaningful messages
   - [ ] Logging includes relevant IDs (traceId, spanId, requestId)
   - [ ] SpringDoc annotations complete for OpenAPI generation
   - [ ] Unit tests cover happy path and error cases
   - [ ] No breaking changes to existing contracts without version bump

2. **Peer Review** (Backend Team Lead or Senior Engineer):
   - Review pull request against the above checklist
   - Verify contract aligns with API design guidelines
   - Check for consistency with similar endpoints
   - Validate security implementation
   - Assess performance implications
   - Ensure proper error handling and edge case coverage

### 2.2 Cross-Team Review

1. **Frontend Team Review**:
   - Frontend engineers review contract for consumability
   - Verify request/response structures match frontend expectations
   - Confirm error handling patterns are frontend-friendly
   - Validate authentication flow works with frontend token management
   - Check that rate limits are appropriate for frontend usage patterns

2. **API Contract Review Board** (Monthly or as needed):
   - For APIs with significant frontend impact or breaking changes
   - Includes frontend lead, backend lead, product lead, and QA lead
   - Reviews API design, documentation, and migration strategy
   - Approves contracts for inclusion in release candidate

### 2.3 Approval Criteria

An API contract is approved for delivery when:

1. All review checklists are satisfied
2. Unit tests achieve ≥80% coverage for new code
3. Contract is backward compatible (or has explicit migration plan)
4. Documentation is complete and accurate
5. No security vulnerabilities identified
6. Performance impact assessed and within acceptable limits
7. Frontend team confirms contract is consumable

## 3. Delivery and Deployment Protocol

### 3.1 Development and Testing

1. **Branch Creation**: Feature branch from `main` with naming convention `feature/api-{resource}-{description}`
2. **Local Development**:
   - Implement contract in controller and service layers
   - Write unit tests using JUnit 5 and Mockito
   - Run locally: `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
   - Test manually using curl or Postman against local instance
3. **Contract Testing**:
   - Validate OpenAPI/Swagger generation at `http://localhost:8080/v3/api-docs`
   - Verify Swagger UI renders correctly at `http://localhost:8080/swagger-ui.html`
   - Confirm request/response schemas match expectations

### 3.2 Continuous Integration

1. **Pull Request Trigger**: Opening PR triggers GitHub Actions workflow
2. **CI Pipeline Stages**:
   - **Build & Test**: Compilation, unit tests, code quality checks
   - **Contract Validation**: 
     - OpenAPI schema generation validation
     - Breaking change detection (if enabled)
     - Documentation completeness check
   - **Security Scanning**: Dependency vulnerability scan, SAST
   - **Integration Testing**: Testcontainers-based MySQL tests
   - **Performance Testing**: Basic load validation (where configured)
3. **Merge Requirements**:
   - All CI checks must pass
   - Minimum 2 approvals from backend team (1 must be tech lead or architect)
   - Frontend team approval for user-impacting changes
   - No merge conflicts with `main` branch

### 3.3 Deployment Strategy

1. **Staging Deployment**:
   - Auto-deploy to staging environment on merge to `main`
   - Smoke tests run against deployed staging instance
   - Contract verification: `curl -s https://staging-api.bhukkad.com/v3/api-docs | jq .`
   - Frontend team performs contract validation against staging
2. **Production Deployment**:
   - Follows GitHub Actions `production.yml` workflow
   - Blue/green deployment strategy via Kubernetes
   - Traffic shifting with 5/10/25/50/100% increments
   - Health checks and rollback on anomaly detection
   - Post-deployment contract verification in production

### 3.4 Contract Publication

Upon successful production deployment:

1. **OpenAPI Specification**: Available at `https://api.bhukkad.com/v3/api-docs`
2. **Swagger UI**: Available at `https://api.bhukkad.com/swagger-ui.html`
3. **Version Header**: All responses include `X-API-Version: 1`
4. **Deprecation Headers**: Added when applicable per RFC 8594
5. **Notification**: Release notes published to internal API changelog channel

## 4. Contract Lifecycle Management

### 4.1 Backward Compatibility

Bhukkad API follows these compatibility rules:

1. **Additive Changes Only** (within same version):
   - Adding new endpoints: ALLOWED
   - Adding new optional request fields: ALLOWED (with defaults)
   - Adding new response fields: ALLOWED
   - Adding new HTTP methods to existing endpoints: ALLOWED
   - Adding new enum values: ALLOWED (frontend must ignore unknown)

2. **Breaking Changes** (require version bump):
   - Removing endpoints: REQUIRES NEW VERSION
   - Removing request/response fields: REQUIRES NEW VERSION
   - Changing data types: REQUIRES NEW VERSION
   - Changing HTTP methods: REQUIRES NEW VERSION
   - Changing authentication requirements: REQUIRES NEW VERSION
   - Making optional fields required: REQUIRES NEW VERSION
   - Changing HTTP status codes for success cases: REQUIRES NEW VERSION

### 4.2 Deprecation Policy

1. **Deprecation Notice**: 
   - Add `@Deprecated` annotation to controller method
   - Include `Deprecation` header in responses: `Deprecation: true; sunset="2026-12-31"`
   - Add warning to OpenAPI description
   - Maintain deprecated endpoint for minimum 6 months

2. **Sunset Process**:
   - After sunset date, endpoint returns 410 Gone
   - Removal requires explicit approval from API Contract Review Board
   - All clients must have migrated prior to sunset

### 4.3 Contract Evolution Tracking

1. **Changelog**: Maintained in `docs/api/changelog.md`
2. **Version Matrix**: Tracks which clients consume which API versions
3. **Usage Monitoring**: 
   - Track endpoint usage via Prometheus metrics
   - Identify low-usage endpoints for potential deprecation
   - Monitor for clients using deprecated functionality

## 5. Technical Implementation Details

### 5.1 Standard Response Format

All successful API responses use the `ApiResponse<T>` wrapper:

```json
{
  "success": true,
  "message": "Human-readable status message",
  "data": { /* payload */ },
  "timestamp": "ISO-8601 timestamp",
  "traceId": "unique request identifier",
  "spanId": "OpenTelemetry span ID (when tracing enabled)",
  "requestId": "alternative request identifier"
}
```

Error responses follow the same format with `success: false`.

### 5.2 Request Validation

Validation occurs at multiple layers:

1. **Handler Method Level** (`@Valid @RequestBody`):
   - Jakarta Validation constraints on DTO fields
   - Automatic 400 Bad Request with field-level error details
   - Example: `@NotBlank @Size(max=100) String fieldName`

2. **Service Layer**:
   - Business rule validation (duplicates, state transitions, etc.)
   - Throws `BusinessException` converted to appropriate HTTP status
   - Examples: insufficient funds, invalid state transition, resource not found

3. **Controller Advice** (`GlobalExceptionHandler`):
   - Centralized exception handling
   - Maps exceptions to HTTP status codes:
     - `BusinessException` → 400 Bad Request
     - `ResourceNotFoundException` → 404 Not Found
     - `AccessDeniedException` → 403 Forbidden
     - `MethodArgumentNotValidException` → 400 Bad Request (validation details)
     - `HttpMessageNotReadableException` → 400 Bad Request (malformed JSON)
     - `HttpRequestMethodNotSupportedException` → 405 Method Not Allowed
     - All others → 500 Internal Server Error (with error ID for support)

### 5.3 Security Implementation

1. **Authentication**:
   - JWT Bearer tokens validated via `JwtAuthenticationFilter`
   - Token expiration: 24 hours (access), 7 days (refresh)
   - Refresh token rotation to prevent replay attacks
   - Blacklist on logout/password change

2. **Authorization**:
   - Method-level security via `@PreAuthorize`
   - Spring EL expressions checking user roles and permissions
   - Examples: `hasRole('CUSTOMER')`, `hasAnyRole('RESTAURANT_OWNER', 'ADMIN')`

3. **Additional Security Layers**:
   - Rate limiting via `@RateLimited` (Redis-backed)
   - WAF filtering for common attacks (SQLi, XSS)
   - Security headers (CSP, HSTS, X-Frame-Options)
   - CORS configuration restricted to trusted origins
   - Input sanitization where appropriate

### 5.4 Idempotency Implementation

For state-changing operations (POST, PUT, PATCH):

1. **Idempotency-Key Header**:
   - Client-generated UUIDv4 recommended
   - Server validates format and stores for 24 hours
   - Key format: `[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12}`

2. **Implementation Pattern**:
   ```java
   // In controller
   @PostMapping("/resource")
   public ResponseEntity<ApiResponse<ResponseT>> createResource(
           @Valid @RequestBody RequestT request,
           @RequestHeader("Idempotency-Key") String idempotencyKey) {
       
       requireIdempotencyKey(idempotencyKey); // Validates format
       
       // Check if processed before
       ApiResponse<ResponseT> cached = idempotencyStore.get(idempotencyKey);
       if (cached != null) {
           return ResponseEntity.ok(cached);
       }
       
       // Process request
       ResponseT result = service.process(request);
       
       // Cache successful response
       ApiResponse<ResponseT> response = ApiResponse.success("Success", result);
       idempotencyStore.put(idempotencyKey, response);
       
       return ResponseEntity.ok(response);
   }
   ```

3. **Storage**:
   - Primary: Redis with TTL 24 hours
   - Fallback: Database table for persistence across restarts
   - cleanup job removes expired entries

### 5.5 Rate Limiting

Implemented via Aspect-Oriented Programming:

1. **Configuration**: `application.yml` under `app.rate-limit.buckets`
2. **Annotation**: `@RateLimited` on controller methods
3. **Implementation**:
   - Custom aspect intercepts annotated methods
   - Uses Redis Lua scripts for atomic increment and expiry
   - Returns 429 Too Many Requests with `Retry-After` header
   - Key format: `bhukkad:rate-limit:{bucket}:{identifier}`
   - Identifier varies by endpoint (user ID, IP, device fingerprint, etc.)

### 5.6 Caching Strategy

Multi-layer caching approach:

1. **Local Cache** (Caffeine):
   - Per-instance, fastest access
   - TTL configured per cache bucket in `application.yml`
   - Used for frequently changing, low-cardinality data

2. **Redis Cache** (L2):
   - Shared across instances
   - TTL configured per cache bucket
   - Used for data requiring consistency across instances
   - Cache-aside pattern with single-flight protection
   - Pub/sub invalidation for immediate consistency

3. **Cache Buckets** (examples):
   - `menu-item`: 900 seconds (15 minutes)
   - `restaurant-list`: 600 seconds (10 minutes)
   - `home-feed`: 60 seconds (1 minute)
   - `order`: 300 seconds (5 minutes) - short due to frequent updates
   - `review`: 1800 seconds (30 minutes)

## 6. Edge Cases and Error Handling

### 6.1 Common Error Scenarios

| HTTP Status | Scenario | Client Handling Guidance |
|-------------|----------|--------------------------|
| 400 Bad Request | Validation failure | Display field-level errors to user |
| 401 Unauthorized | Missing/invalid/expired token | Redirect to login flow |
| 403 Forbidden | Insufficient permissions | Show permission error message |
| 404 Not Found | Resource doesn't exist | Show "not found" UI |
| 409 Conflict | Idempotency replay in progress | Show "please wait" spinner |
| 429 Too Many Requests | Rate limit exceeded | Honor `Retry-After` header, show throttle message |
| 500 Internal Server Error | Unexpected server error | Show generic error, offer retry |
| 503 Service Unavailable | System overload/maintenance | Honor `Retry-After`, show maintenance page |

### 6.2 Specific Error Handling Patterns

#### 6.2.1 Idempotency Conflicts
- **Scenario**: Same `Idempotency-Key` used with different request body
- **Response**: 409 Conflict with message "Idempotency key already used with different request"
- **Client Action**: Generate new key and retry with original intent

#### 6.2.2 Partial Failures
- **Scenario**: Batch operations where some items succeed, others fail
- **Response**: 200 OK with `success: true` but `data` contains failure details
- **Example**: Batch order creation - successful orders returned, failed ones listed in `errors[]`

#### 6.2.3 Degraded Service
- **Scenario**: Non-critical service unavailable (e.g., recommendation engine)
- **Response**: 200 OK with degraded functionality clearly indicated
- **Example**: Empty recommendations array with flag `fallbackUsed: true`

#### 6.2.4 Maintenance Mode
- **Scenario**: Planned maintenance or emergency shutdown
- **Response**: 503 Service Unavailable with `Retry-After` and maintenance window
- **Client Action**: Display maintenance page, retry after specified time

### 6.3 Error Response Format

All errors follow this structure:

```json
{
  "success": false,
  "message": "Human-readable error message",
  "data": null,
  "timestamp": "2026-08-25T18:42:21Z",
  "traceId": "8060e534c23630e6a1393f746a07029d",
  "spanId": "09c46cd3bd00279e",
  "requestId": "862681db"
}
```

Validation errors include field details:

```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "fieldErrors": [
      {
        "field": "email",
        "message": "must be a valid email address",
        "rejectedValue": "invalid-email"
      },
      {
        "field": "password",
        "message": "must be at least 8 characters",
        "rejectedValue": "123"
      }
    ]
  },
  "timestamp": "...",
  "traceId": "...",
  "spanId": "...",
  "requestId": "..."
}
```

## 7. Dependency Management

### 7.1 Internal Dependencies

API contracts frequently depend on internal services and components:

1. **Service Layer Dependencies**:
   - Controllers depend on `@Service` interfaces, not implementations
   - Dependency injection via constructor injection (required by Lombok `@RequiredArgsConstructor`)
   - Circular dependencies avoided through proper layering (controllers → services → repositories)

2. **Database Dependencies**:
   - Read operations use `@UseReadReplica` for read scaling
   - Write operations target primary database
   - Transactions managed at service layer with `@Transactional`
   - Read-only transactions optimized: `@Transactional(readOnly = true)`

3. **Cache Dependencies**:
   - Cache invalidation via `DistributedCacheInvalidator` pub/sub
   - Cache warming strategies for critical paths
   - Fallback to database on cache misses (cache-aside pattern)

4. **External Service Dependencies**:
   - Circuit breaker pattern for external calls (Resilience4j)
   - Timeout and retry configurations per service
   - Fallback responses when external services unavailable
   - Examples: Payment gateway, email/SMS providers, geocoding services

### 7.2 Versioning Dependencies

When APIs depend on other internal APIs:

1. **Explicit Version Declaration**:
   - Service clients specify required API version
   - Version compatibility matrix maintained
   - Breaking changes in dependencies trigger version bump in dependent API

2. **Contract Testing**:
   - Consumer-driven contract testing (Pact) for critical internal APIs
   - Provider verification ensures contracts are not broken
   - Consumer tests validate ability to handle provider responses

### 7.3 Third-Party Library Management

1. **Approved Libraries**:
   - All dependencies must be approved via internal security review
   - Regular vulnerability scanning (Dependabot, Snyk)
   - License compliance checking

2. **API-Specific Considerations**:
   - Jackson version must align with Spring Boot version
   - Validation API (Jakarta) version compatibility
   - SpringDoc version matched to OpenAPI 3.0 specification support
   - Lombok version compatible with Java 17 and annotation processors

## 8. API Documentation Integration

### 8.1 Existing Documentation Enhancement

To integrate with existing documentation, update these files:

#### 8.1.1 Core Workflow Documentation (`docs/api-workflow.md`)
Add a new section:

```
## 11. API Contract Lifecycle Management

This section describes the process for defining, reviewing, delivering, and maintaining API contracts.

### 11.1 Contract Definition
[Summary of section 1 from this document]

### 11.2 Review Process
[Summary of section 2 from this document]

### 11.3 Delivery Procedure
[Summary of section 3 from this document]

### 11.4 Lifecycle Management
[Summary of section 4 from this document]
```

#### 8.1.2 API Reference Documentation (`docs/api/*.md`)
Ensure each API reference file includes:

1. **Contract Metadata** at top of file:
   ```markdown
   # Orders API
   
   **Contract Version**: 1.0.0
   **Last Updated**: 2026-08-25
   **Maintainer**: backend-team@bhukkad.com
   **Status**: Stable
   **Deprecation Notice**: None
   ```

2. **Implementation Notes** section detailing:
   - How the contract is implemented in code
   - Any implementation-specific behaviors
   - Performance characteristics
   - Known limitations or planned enhancements

### 8.2 Auto-Generated Documentation Maintenance

Ensure SpringDoc annotations are complete and accurate:

1. **Required Annotations**:
   - `@Tag` on controller classes for grouping
   - `@Operation` on each method with:
     - `summary`: Concise purpose description
     - `description`: Detailed explanation when needed
     - Include `responses` array for common error cases when non-standard

2. **DTO Documentation**:
   - Use `@Schema` on DTO classes and fields when needed
   - Provide `description`, `example`, `minimum`, `maximum`, `pattern` as appropriate
   - Mark required fields appropriately (validation annotations imply required)

3. **Example Values**:
   - Use `@Schema(example = "...")` on fields
   - Provide realistic examples that match business domain
   - Avoid unrealistic or placeholder values

### 8.3 Documentation Validation Process

1. **Local Verification**:
   - Start application locally: `mvn spring-boot:run`
   - Verify Swagger UI loads: `http://localhost:8080/swagger-ui.html`
   - Check that all new endpoints appear correctly grouped
   - Verify request/response schemas match expectations
   - Test "Try it out" functionality with valid and invalid inputs

2. **CI Validation**:
   - GitHub Actions validates OpenAPI schema generation
   - Schema comparison against baseline to detect unintended changes
   - Link checker validates any URLs in descriptions or examples
   - Spell check documentation for professionalism

## 9. Best Practices and Guidelines

### 9.1 RESTful Design Principles

1. **Resource-Oriented**:
   - URLs represent resources, not actions
   - Use nouns, not verbs in paths: `/orders` not `/createOrder`
   - HTTP methods indicate actions: GET (read), POST (create), PUT/PATCH (update), DELETE (remove)

2. **Stateless Interactions**:
   - Each request contains all information needed
   - Session state stored client-side (tokens) or in shared storage (Redis)
   - Server affinity not required for correct operation

3. **Representational Transfer**:
   - Multiple representations possible (JSON primary, CSV for exports)
   - Content negotiation via `Accept` header
   - Standardized error formats enable generic error handling

### 9.2 API Design Guidelines

1. **Consistency**:
   - Follow existing patterns in codebase for similar operations
   - Use same field names for same concepts across endpoints
   - Consistent error response structure
   - Uniform date/time format (ISO-8601 UTC)

2. **Clarity**:
   - Self-describing field names
   - Avoid abbreviations unless universally understood in domain
   - Clear distinction between similar concepts (e.g., `estimatedDeliveryAt` vs `liveEtaAt`)
   - Comprehensive Javadoc and OpenAPI descriptions

3. **Evolvability**:
   - Design for forward compatibility
   - Reserve fields for future use (commented in DTOs)
   - Use versioned vendor prefixes for experimental features: `X-Bhukkad-Experimental-Feature`
   - Plan for deprecation from the start

### 9.3 Frontend-Backend Contract Alignment

1. **Joint Design Sessions**:
   - API changes affecting UI designed collaboratively
   - Mockups or prototypes reviewed before implementation
   - Frontend feedback incorporated during definition phase

2. **Consumer-Driven Contracts**:
   - Frontend teams can submit test cases for expected API behavior
   - These tests become part of backend validation suite
   - Critical user flows have executable specifications

3. **Breaking Change Communication**:
   - Minimum 4-week notice for breaking changes
   - Migration guides provided for significant changes
   - Feature flags enable gradual rollout when appropriate
   - Sunset dates clearly communicated

### 9.4 Quality Assurance

1. **Testing Strategy**:
   - Unit tests: ≥80% coverage for new code
   - Contract tests: Validate request/response schemas
   - Integration tests: Testcontainers-based database tests
   - Performance tests: Validate under expected load
   - Security tests: Validate authentication/authorization boundaries

2. **Review Checklist**:
   - [ ] Follows REST conventions and HTTP semantics
   - [ ] Proper status codes for all scenarios
   - [ ] Input validation comprehensive
   - [ ] Authentication and authorization correct
   - [ ] Rate limiting applied where needed
   - [ ] Idempotency implemented for state-changing ops
   - [ ] Standard response wrapper used
   - [ ] Error handling complete and informative
   - [ ] Logging includes traceability IDs
   - [ ] SpringDoc annotations accurate and complete
   - [ ] No security vulnerabilities introduced
   - [ ] Performance impact assessed
   - [ ] Documentation updated and accurate
   - [ ] Frontend team has reviewed and approved

### 9.5 Security Guidelines

1. **Authentication**:
   - Never expose tokens in URLs or logs
   - Use HTTP-only cookies for refresh tokens where appropriate
   - Implement proper token expiration and rotation
   - Protect against CSRF where session cookies used

2. **Authorization**:
   - Principle of least privilege
   - Deny by default, explicitly grant permissions
   - Regular audit of role definitions and permissions
   - Protect against insecure direct object references (IDOR)

3. **Data Protection**:
   - Encrypt sensitive data at rest and in transit
   - Mask PII in logs and error messages
   - Implement data minimization principles
   - Regular security scanning and penetration testing

4. **Input Validation**:
   - Validate all input: parameters, headers, body
   - Use allowlists where possible (reject unknown)
   - Implement proper output encoding to prevent XSS
   - Protect against SQL injection via parameterized queries
   - Validate file uploads: type, size, content

---

## Appendix: Reference Implementation

### A.1 Controller Template

```java
package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.ExampleRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.ExampleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.*;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/example")
@RequiredArgsConstructor
@Tag(name = "Example", description = "REST endpoints for Example Resource")
public class ExampleController {

    private final ExampleService exampleService;

    @Operation(
        summary = "Create an example resource",
        description = "Creates a new example resource with the provided details.",
        responses = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Resource created successfully"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "Validation error"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "401",
                description = "Unauthorized"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Forbidden"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded"
            )
        }
    )
    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<ExampleResponse>> createExample(
            @Valid @RequestBody ExampleRequest request,
            @RequestHeader(value = "Idempotency-Key", required = true) String idempotencyKey) {
        
        requireIdempotencyKey(idempotencyKey);
        // Additional validation or preprocessing if needed
        
        ExampleResponse response = exampleService.createExample(request, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.success("Example created successfully", response));
    }

    // Additional methods (get, update, delete) follow similar pattern
    
    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header is required");
        }
        // Additional UUID format validation if desired
        if (!key.matches("[a-z0-9]{8}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{4}-[a-z0-9]{12}")) {
            throw new IllegalArgumentException("Invalid Idempotency-Key format");
        }
    }
}
```

### A.2 Request DTO Template

```java
package com.bhukkad.dto.request;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExampleRequest {
    
    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;
    
    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 1000, message = "Quantity must not exceed 1000")
    private Integer quantity;
    
    @Email(message = "Please provide a valid email address")
    @NotBlank(message = "Email is required")
    private String email;
    
    @Pattern(regexp = "^[A-Z]{2}[0-9]{6}$", message = "Code must be in format XX######")
    private String customCode;
    
    // Additional fields with appropriate validation
}
```

### A.3 Response DTO Template

```java
package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExampleResponse {
    
    private Long id;
    private String name;
    private Integer quantity;
    private String email;
    private String customCode;
    
    // Audit fields
    private Long createdBy;
    private java.time.Instant createdAt;
    private Long modifiedBy;
    private java.time.Instant modifiedAt;
    
    // Additional fields as needed
}
```

---

## Change Log

| Version | Date | Changes | Author |
|---------|------|---------|--------|
| 1.0.0 | 2026-08-25 | Initial API Contract Delivery Process documentation | Backend Team |

---
*This document is the single source of truth for API contract delivery processes in the Bhukkad Food Delivery System. All API changes must follow the procedures outlined herein.*