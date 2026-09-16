# Bhukkad Backend — API Architecture Knowledge Transfer (Detailed E2E)

## Audience
New backend engineers / interns who need to understand how every major API flows through the bhukkad codebase, from HTTP request to database and back, with complete step-by-step detail.

## How to read this doc
- Each section covers **one bounded context / service**.
- Every endpoint is explained with:
  1. **Purpose** (plain English)
  2. **Request/Response format** (actual JSON shapes)
  3. **E2E data flow** (numbered steps, layer by layer)
  4. **Real code snippets** (from the actual codebase)
  5. **Database impact** (exact tables and SQL patterns)
  6. **Caching behavior** (what is cached, TTLs, invalidation)
  7. **Security/auth rules** (exact checks performed)
  8. **Error handling** (every failure mode)
  9. **Service-to-service calls** (mesh auth, timeouts, retries)
  10. **Common pitfalls** (what interns often get wrong)

---



## 1. Overall Request Lifecycle

```text
Client (mobile / web)
    │
    ▼
┌─────────────────────────────────────────────────────────────┐
│  Spring Cloud Gateway (8080)                                 │
│  ┌─────────────┐  ┌──────────────┐  ┌─────────────────────┐ │
│  │ Route       │  │ Circuit      │  │ Rate Limiter         │ │
│  │ Resolver    │  │ Breaker      │  │ (Redis Lua)          │ │
│  │ (path       │  │ (Resilience4j│  │                     │ │
│  │  predicate) │  │  per route)  │  │                     │ │
│  └─────────────┘  └──────────────┘  └─────────────────────┘ │
└────────────────────────────┬────────────────────────────────┘
                             │ HTTP/1.1 or HTTP/2
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  Backend Service (e.g. order:8092)                           │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Security    │  │ Controller  │  │ Service Layer        │  │
│  │ Filter      │  │ (HTTP →     │  │ (business rules,     │  │
│  │ (JWT        │  │  DTO →      │  │  transactions,       │  │
│  │  validate)  │  │  Service)   │  │  outbox events)      │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│                             │                               │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │ Repository Layer                                        │ │
│  │ - JPA/Hibernate or JdbcTemplate                         │ │
│  │ - SQL generation                                         │ │
│  │ - Transaction boundaries                                 │ │
│  └──────────────────────────┬──────────────────────────────┘ │
│                             │                               │
│  ┌──────────────────────────▼──────────────────────────────┐ │
│  │ Infrastructure                                           │ │
│  │ - Redis Cache (L1 Caffeine + L2 Redis)                   │ │
│  │ - Kafka Producer (outbox events)                         │ │
│  │ - HTTP Clients (Feign/WebClient for mesh calls)          │ │
│  └─────────────────────────────────────────────────────────┘ │
└────────────────────────────┬────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│  Response                                                   │
│  JSON body + HTTP status code                               │
│  (streams back through gateway if applicable)               │
└─────────────────────────────────────────────────────────────┘
```

### Cross-cutting concerns (applied at every layer)

| Concern | Implementation | Where |
|---------|---------------|-------|
| **JWT validation** | `PlatformJwtAuthFilter` extracts `TokenPrincipal` from `Authorization: Bearer <token>` | Every service |
| **Rate limiting** | Redis Lua script: `INCR` key + `PEXPIRE` + compare limit | Gateway edge + some service endpoints |
| **Circuit breaker** | Resilience4j: 30% failure threshold, 20s open state, 5 half-open calls | Gateway per upstream |
| **Outbox** | `OutboxClient.save(aggregateType, id, eventType, payload)` inside `@Transactional` | Service layer |
| **Caching** | Two-tier: Caffeine L1 (in-JVM) → Redis L2 (cluster) via `RedisCacheService` | Service layer |
| **Logging** | SLF4J + structured JSON (timestamp, service, level, traceId, userId) | Every class |
| **Metrics** | Micrometer counters/timers/histograms | Service layer |
| **Tracing** | Zipkin/Tempo via Brave/OpenTelemetry | HTTP + Kafka |

---



## 2. Gateway Layer

**File**: `services/gateway/src/main/java/com/bhukkad/gateway/GatewayConfig.java`

### 2.1 How routing works (detailed)

The gateway is a **reactive Spring Cloud Gateway** (Netty, no Tomcat). Routes are defined programmatically using a `RouteLocatorBuilder`. Each route has:
- An **id** (unique name)
- A **predicate** (usually `Path` — which URL patterns to match)
- A **URI** (where to forward)
- Optional **filters** (circuit breaker, rate limiter, etc.)

```java
@Configuration
public class GatewayConfig {

    // These URIs come from application.yml (or environment variables)
    // In production: k8s DNS names like http://restaurant:8091
    // In local dev: http://localhost:8091
    private final String restaurantUri;
    private final String identityUri;
    private final String orderUri;
    private final String paymentUri;
    private final String deliveryUri;
    private final String searchUri;
    private final String surveyUri;
    private final String referralUri;
    private final String supportUri;
    private final String notificationUri;
    private final String adminAnalyticsUri;
    private final String realtimeUri;
    private final String growthUri;
    private final String personalizationUri;

    // Constructor injection (Spring Boot pattern)
    public GatewayConfig(
            @Value("${app.routes.restaurant-uri}") String restaurantUri,
            @Value("${app.routes.identity-uri}") String identityUri,
            @Value("${app.routes.order-uri}") String orderUri,
            @Value("${app.routes.payment-uri}") String paymentUri,
            @Value("${app.routes.delivery-uri}") String deliveryUri,
            @Value("${app.routes.search-uri}") String searchUri,
            @Value("${app.routes.survey-uri}") String surveyUri,
            @Value("${app.routes.referral-uri}") String referralUri,
            @Value("${app.routes.support-uri}") String supportUri,
            @Value("${app.routes.notification-uri}") String notificationUri,
            @Value("${app.routes.admin-analytics-uri}") String adminAnalyticsUri,
            @Value("${app.routes.realtime-uri}") String realtimeUri,
            @Value("${app.routes.growth-uri}") String growthUri,
            @Value("${app.routes.personalization-uri}") String personalizationUri) {
        this.restaurantUri = restaurantUri;
        this.identityUri = identityUri;
        this.orderUri = orderUri;
        this.paymentUri = paymentUri;
        this.deliveryUri = deliveryUri;
        this.searchUri = searchUri;
        this.surveyUri = surveyUri;
        this.referralUri = referralUri;
        this.supportUri = supportUri;
        this.notificationUri = notificationUri;
        this.adminAnalyticsUri = adminAnalyticsUri;
        this.realtimeUri = realtimeUri;
        this.growthUri = growthUri;
        this.personalizationUri = personalizationUri;
    }

    // Route definitions
    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
            // Order matters! Most-specific first.
            // If a broad route like "/api/v1/**" came first, it would
            // swallow narrow routes like "/api/v1/auth/**".
            .route("survey", r -> r
                .path("/api/v1/reviews/survey")
                .uri(surveyUri))
            .route("realtime", r -> r
                .path("/api/v1/live/**")
                .uri(realtimeUri))
            .route("search", r -> r
                .path("/api/v1/search/**")
                .uri(searchUri))
            .route("referral", r -> r
                .path("/api/v1/referral/**", "/api/v1/referrals/**")
                .uri(referralUri))
            .route("support", r -> r
                .path("/api/v1/support/**")
                .uri(supportUri))
            .route("notification", r -> r
                .path("/api/v1/notifications/**")
                .uri(notificationUri))
            .route("admin-analytics", r -> r
                .path("/api/v1/admin/**")
                .uri(adminAnalyticsUri))
            .route("payment", r -> r
                .path("/api/v1/payments/**", "/api/v1/wallet/**")
                .uri(paymentUri))
            .route("delivery", r -> r
                .path("/api/v1/delivery/**", "/api/v1/deliveries/**",
                      "/api/v1/serviceability/**")
                .uri(deliveryUri))
            .route("order", r -> r
                .path("/api/v1/orders/**", "/api/v1/cart/**",
                      "/api/v1/gift-cards/**")
                .uri(orderUri))
            .route("restaurant", r -> r
                .path("/api/v1/restaurants/**", "/api/v1/menu/**",
                      "/api/v1/cuisines/**", "/api/v1/reviews/**",
                      "/api/v1/home/**", "/api/v1/mobile/**",
                      "/api/v1/feed/**", "/api/v1/pricing/**",
                      "/api/v1/inventory/**")
                .uri(restaurantUri))
            .route("identity", r -> r
                .path("/api/v1/auth/**", "/api/v1/customers/**",
                      "/api/v1/affiliate/**")
                .uri(identityUri))
            // Catch-all 404 for unmatched /api/** paths
            .route("unmatched", r -> r
                .path("/api/**")
                .filters(f -> f.setPath("/api/v1/not-found"))
                .uri("http://unmatched"))
            .build();
    }
}
```

### 2.2 E2E data flow for a gateway request

Let's trace `GET /api/v1/restaurants/public?page=0&size=20`:

```
Step 1: Client sends HTTP request
  - Method: GET
  - URL: http://gateway-host:8080/api/v1/restaurants/public?page=0&size=20
  - Headers: Accept: application/json

Step 2: Gateway receives request (Netty channel)
  - Reactive pipeline: Netty → Spring WebFlux

Step 3: Route matching (GatewayConfig)
  - Path: "/api/v1/restaurants/public"
  - Matches: "restaurant" route (predicate: /api/v1/restaurants/**)
  - Target URI: http://restaurant-service:8091

Step 4: Pre-filter chain
  - SecurityHeadersFilter: adds Security-Headers
  - EdgeRateLimitFilter: Redis Lua script checks rate limit for this IP
    * Key: "ratelimit:gateway:<client_ip>"
    * Lua script: INCR + PEXPIRE + check limit in one round-trip
    * If limit exceeded → 429 Too Many Requests
  - CircuitBreaker filter (Resilience4j):
    * State: CLOSED (healthy) → allow
    * State: OPEN → reject with 503 + Retry-After header
    * State: HALF_OPEN → allow 5 test requests

Step 5: Forward to backend
  - Netty client sends HTTP/1.1 or HTTP/2 request to restaurant service
  - Headers forwarded (except hop-by-hop headers removed)

Step 6: Backend processes request (see Section 4.1)

Step 7: Response streams back
  - Restaurant service returns JSON
  - Gateway may apply post-filters (e.g., add X-Response-Time)
  - Response sent to client

Total gateway overhead: ~2-5ms (mostly network)
```

### 2.3 Gateway filters in detail

| Filter | Class | Purpose | Configuration |
|--------|-------|---------|---------------|
| CircuitBreaker | `Resilience4jCircuitBreakerFilter` | Opens after 30% failures in sliding window of 20 requests; waits 20s before half-open | `app.resilience4j.circuitbreaker` |
| RequestRateLimiter | `RedisRateLimiter` | Token bucket per client IP; replenish rate 100 tokens/sec, burst 200 | `app.rate-limit.*` |
| SecurityHeaders | `SecurityHeadersFilter` | Adds `X-Content-Type-Options`, `X-Frame-Options`, `HttpOnly` cookies | Hardcoded |
| ConsistentHashSseFilter | Custom | Pins SSE connections to same realtime pod using consistent hashing on `userId` | `/api/v1/live/**` only |
| KillSwitch | `EdgeKillSwitchFilter` | Feature flags from Redis hash `bhukkad:feature-flag:overrides` | Per-route |

---



## 3. Identity Service

**Port**: 8081  
**Database**: `identity_db` (PostgreSQL)  
**Auth**: JWT HS256/RS256 via `JwtService` in `platform-lib`  
**Key entities**: `User`, `Customer`, `RestaurantOwner`, `DeliveryAgent`, `Address`, `RefreshToken`

### 3.1 POST /api/v1/auth/register

**Purpose**: Create a new user account. This is the entry point for all users (customers, owners, agents).

#### 3.1.1 Request format
```json
{
  "email": "alice@example.com",
  "password": "SecurePass123!",
  "fullName": "Alice Smith",
  "phoneNumber": "9199999999",
  "role": "CUSTOMER",
  "referralCode": "OPTIONAL_CODE"
}
```

#### 3.1.2 Response format (success)
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "customerId": 42,
  "fullName": "Alice Smith",
  "role": "CUSTOMER",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}
```

#### 3.1.3 Controller code (real)
```java
@PostMapping("/api/v1/auth/register")
public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
    // The @Valid annotation triggers Jakarta Validation BEFORE this method runs.
    // If validation fails, a MethodArgumentNotValidException is thrown and
    // handled by GlobalExceptionHandler → 400 Bad Request.
    
    // 1. Rate limit check (Redis)
    //    LoginLockoutService uses a Redis key per IP: "loginlockout:<ip>"
    //    INCR + PEXPIRE(5 minutes). If count > 1000, throw BusinessException(429).
    
    // 2. Check if email already exists
    if (userRepository.existsByEmail(request.email())) {
        throw new BusinessException("Email already registered"); // → 409
    }
    
    // 3. Hash password (BCrypt with strength 10)
    String hashed = passwordEncoder.encode(request.password());
    
    // 4. Create User entity
    User user = new User();
    user.setEmail(request.email());
    user.setPasswordHash(hashed);
    user.setRole(User.UserRole.valueOf(request.role())); // CUSTOMER, OWNER, AGENT
    user.setActive(true);
    user = userRepository.save(user);
    
    // 5. Create role-specific entity
    if ("CUSTOMER".equalsIgnoreCase(request.role())) {
        Customer customer = new Customer();
        customer.setUserId(user.getId());
        customer.setFullName(request.fullName());
        customer.setPhone(request.phoneNumber());
        customerRepository.save(customer);
    } else if ("RESTAURANT_OWNER".equalsIgnoreCase(request.role())) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setUserId(user.getId());
        ownerRepository.save(owner);
    } else if ("DELIVERY_AGENT".equalsIgnoreCase(request.role())) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setUserId(user.getId());
        agent.setName(request.fullName());
        agent.setPhone(request.phoneNumber());
        agentRepository.save(agent);
    }
    
    // 6. Publish outbox event (inside same transaction!)
    outboxClient.save("User", user.getId(), "UserRegistered", Map.of(
        "userId", user.getId(),
        "email", user.getEmail(),
        "role", user.getRole()
    ));
    
    // 7. Generate JWT
    String token = jwtService.generateToken(user.getId(), user.getRole(), "CUSTOMER");
    String refreshToken = refreshTokenService.create(user.getId());
    
    // 8. Return response
    return new AuthResponse(token, user.getId(), request.fullName(), user.getRole(), refreshToken);
}
```

#### 3.1.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request Arrives
  - POST /api/v1/auth/register
  - Content-Type: application/json
  - Body: {"email":"alice@example.com","password":"SecurePass123!",...}

Step 2: Spring MVC Dispatcher Servlet
  - Finds @PostMapping method in IdentityController
  - Uses Jackson to deserialize JSON → RegisterRequest record
  - Jakarta Validation (@Valid) runs:
    * @NotBlank on email, fullName, password
    * @Email on email
    * @Size(min=8, max=128) on password
    * @Pattern on role (must be CUSTOMER|RESTAURANT_OWNER|DELIVERY_AGENT)
  - If validation fails → MethodArgumentNotValidException → 400

Step 3: Rate Limit Check (Redis)
  - LoginLockoutService.registerRateLimit()
  - Redis key: "loginlockout:<client_ip>"
  - Lua script (atomic):
    local key = KEYS[1]
    local limit = tonumber(ARGV[1])
    local window = tonumber(ARGV[2])
    local current = redis.call('INCR', key)
    if current == 1 then
      redis.call('PEXPIRE', key, window)
    end
    if current > limit then
      return 0  -- rejected
    end
    return 1  -- allowed
  - Limit: 1000 registrations per 5 minutes per IP
  - If exceeded → BusinessException("Too many requests") → 429

Step 4: Email Uniqueness Check
  - userRepository.existsByEmail("alice@example.com")
  - SQL: SELECT EXISTS(SELECT 1 FROM users WHERE email = ?)
  - If exists → BusinessException("Email already registered") → 409 Conflict

Step 5: Password Hashing
  - passwordEncoder.encode("SecurePass123!")
  - BCrypt with strength 10 (2^10 = 1024 rounds)
  - Output: "$2b$10$pR1oqzVQuKqrVj9ZME9C9ugYVDc3gCxaRmJd/8iPdeGFF7h361h1W"
  - This takes ~100ms (intentionally slow to prevent brute-force)

Step 6: User Entity Creation
  - new User()
  - Set fields: email, passwordHash, role=CUSTOMER, active=true
  - userRepository.save(user)
  - SQL: INSERT INTO users (email, password_hash, role, active, ...) VALUES (?, ?, ?, ...)
  - Returns user with generated ID (e.g., id=42)

Step 7: Role-Specific Entity Creation
  - Since role=CUSTOMER:
    new Customer()
    customer.setUserId(42)
    customer.setFullName("Alice Smith")
    customer.setPhone("9199999999")
    customerRepository.save(customer)
    SQL: INSERT INTO customers (user_id, full_name, phone) VALUES (42, 'Alice Smith', '9199999999')

Step 8: Outbox Event (Critical!)
  - outboxClient.save("User", 42, "UserRegistered", {...})
  - This happens INSIDE the same @Transactional method as the user insert
  - SQL (atomic):
    INSERT INTO orders (...) VALUES (...);  -- user insert
    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload, status)
    VALUES ('User', 42, 'UserRegistered', '{"userId":42,...}', 'PENDING');
  - If the service crashes after user insert but before outbox insert,
    the outbox row is missing → event is lost.
  - If it crashes after both, the outbox row exists → event will be published.

Step 9: JWT Generation
  - jwtService.generateToken(42, "CUSTOMER", "CUSTOMER")
  - Creates JWT with payload:
    {
      "sub": "42",           // Subject = user ID (numeric string)
      "email": "alice@example.com",
      "role": "CUSTOMER",    // Role from User entity
      "scope": "CUSTOMER",   // Scope for authorization
      "typ": "access",
      "jti": "a1b2c3d4-...", // Unique token id (UUID)
      "iat": 1726461600,     // Issued at (Unix timestamp)
      "exp": 1726462500      // Expires at (iat + 900 = 15 minutes)
    }
  - Signs with RS256 (RSA private key from app.jwt.signing-key)
  - Header: {"alg":"RS256","typ":"access","kid":"key-id-1"}

Step 10: Refresh Token Creation
  - refreshTokenService.create(42)
  - Generates random 64-byte token
  - SQL: INSERT INTO refresh_tokens (user_id, token, expires_at) VALUES (42, 'random...', NOW() + 7 days)

Step 11: Return Response
  - HTTP 200 OK
  - Body: {"token":"<jwt>","customerId":42,"fullName":"Alice Smith","role":"CUSTOMER","refreshToken":"<refresh>"}
  - Client stores token in memory/secure storage

Step 12: Client uses token
  - Subsequent requests: Authorization: Bearer <jwt>
  - Gateway and backend validate on every request
```

#### 3.1.5 Database tables touched

| Table | Operation | Purpose |
|-------|-----------|---------|
| `users` | INSERT | Create user account |
| `customers` | INSERT | Create customer profile (if role=CUSTOMER) |
| `restaurant_owners` | INSERT | Create owner profile (if role=OWNER) |
| `delivery_agents` | INSERT | Create agent profile (if role=AGENT) |
| `outbox_events` | INSERT | Publish UserRegistered event |
| `refresh_tokens` | INSERT | Create refresh token |

#### 3.1.6 Caching behavior

- **No caching for registration** (it's a write operation).
- Rate limit state is in Redis (`loginlockout:<ip>`), TTL 5 minutes.

#### 3.1.7 Security considerations

- **Rate limiting**: Prevents spam registrations (1000 per 5min per IP).
- **Password hashing**: BCrypt with strength 10 (~100ms per hash) prevents brute-force.
- **Email uniqueness**: Enforced at DB level + application level.
- **Role validation**: Only CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT allowed (no ADMIN self-registration).

#### 3.1.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 400 Bad Request | Validation failure | Password too short, invalid email format |
| 409 Conflict | Email already exists | Registering with same email twice |
| 429 Too Many Requests | Rate limit exceeded | >1000 registrations from same IP in 5min |
| 500 Internal Server Error | Database down, Redis down | Connection pool exhausted |

---

### 3.2 POST /api/v1/auth/login

**Purpose**: Authenticate an existing user and issue an access JWT plus a rotating refresh token. This is the session-establishing endpoint for all client types (customer, owner, agent). Admins are authenticated here too, but admin *accounts* are seeded by the bootstrap job, not self-registered.

#### 3.2.1 Request format
```json
{
  "email": "alice@example.com",
  "password": "SecurePass123!",
  "deviceId": "ios-8675309",
  "totpCode": "123456"
}
```
- `deviceId` (optional) is a client-reported device label, capped at 64 chars by `@Size`.
- `totpCode` (optional) is required only when the account has TOTP MFA enabled (`users.totp_enabled = true`).

#### 3.2.2 Response format (success)
```json
{
  "token": "eyJhbGciOiJSUzI1NiIsImtpZCI6Ijk0MTIz...",
  "customerId": 42,
  "fullName": "Alice Smith",
  "role": "CUSTOMER",
  "refreshToken": "8c2b...f3a1"
}
```
- The access token is an **RS256** JWT (15-minute TTL by default via `app.jwt.access-ttl-minutes`). The refresh token is a 64-byte random opaque secret stored **hashed** (SHA-256) server-side with a 7-day expiry.

#### 3.2.3 Controller code (real)
```java
@PostMapping("/auth/login")
@RateLimited(bucket = "auth-login", limit = LOGIN_RATE_LIMIT, windowSeconds = AUTH_RATE_WINDOW_SECONDS)
public AuthResponse login(@Valid @RequestBody LoginRequest request,
                          @RequestHeader(value = "User-Agent", required = false) String userAgent,
                          HttpServletRequest httpRequest) {
    String ip = LoginLockoutService.remoteIp(httpRequest);
    loginLockout.assertAllowed(request.email(), ip);
    try {
        var login = identityService.login(request.email(), request.password(),
                request.deviceId(), userAgent, request.totpCode());
        loginLockout.recordSuccess(request.email(), ip);
        return toAuthResponse(login);
    } catch (UnauthorizedException e) {
        loginLockout.recordFailure(request.email(), ip);
        throw e;   // → 401
    }
}
```

#### 3.2.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request arrives at the gateway
  - POST /api/v1/auth/login
  - Headers: Content-Type: application/json, X-Forwarded-For present (behind proxy)
  - Body: {"email":"alice@example.com","password":"SecurePass123!","deviceId":"ios-..."}

Step 2: Gateway routing
  - Path predicate "/api/v1/auth/**" matches the "identity" route → forwards to
    http://identity-service:8081
  - EdgeRateLimitFilter runs a Redis Lua token-bucket (per client IP) before forwarding.
  - If the IP is over the gateway-level limit → 429 here (no backend call).

Step 3: Spring MVC deserialization + validation
  - IdentityController.login() is invoked.
  - Jackson maps JSON → LoginRequest record.
  - @Valid runs Jakarta constraints:
      * @NotBlank @Email on email
      * @NotBlank on password
      * @Size(max=64) on deviceId
  - Any constraint violation → MethodArgumentNotValidException →
    GlobalExceptionHandler → 400 {error:VALIDATION_ERROR,...}.

Step 4: Rate limiting (two layers)
  4a. Method-level @RateLimited (Redis Lua bucket "auth-login", 30 calls / 300s per IP).
      If exceeded → BusinessException("Too many requests") → 429 + Retry-After.
  4b. Per-(email, IP) brute-force lockout (LoginLockoutService.assertAllowed):
      - Redis key: "loginlockout:<email>:<ip>"
      - Lua script atomically INCR + PEXPIRE(300s); if count > 5 in the current
        exponential window → locked → 429 + Retry-After header.
      - This is the per-account shield; the @RateLimited bucket is the per-IP shield.

Step 5: Credential verification (IdentityService.login, @Transactional)
  - customerRepository.findByEmailAndIsActiveTrue(email)
      SQL: SELECT * FROM customers WHERE email = ? AND is_active = true
  Case A — customer found:
    - passwordService.matches(rawPassword, storedHash)
        * storedHash is BCrypt (strength 10). matches() is constant-time-ish.
        * Mismatch → throw UnauthorizedException("Invalid email or password") → 401.
        * A failed match is recorded by the controller (4b) so lockout escalates.
  - TOTP (feature #5): if totpService.isRequired(userId):
        missing totpCode → UnauthorizedException("TOTP code required")  → 401
        wrong code      → UnauthorizedException("Invalid TOTP code")     → 401
  Case B — no customer, but admin row exists (adminRepository.findByEmail):
    - Validates users.active flag + BCrypt match on admin row.
    - Mismatched/deactivated → 401. Admins are never TOTP-gated here.
  Case C — neither: throw UnauthorizedException("Invalid email or password") → 401.
  * Uniform error text in every failure case prevents account enumeration.*

Step 6: Issue access JWT (IdentityService)
  - resolveScope(userId) → reads users.role → e.g. "CUSTOMER".
  - jwtService.issue(userId, email, scope):
      * subject = String.valueOf(userId)   (numeric string)
      * claims: email, scope, role, typ="access", jti (UUID), iss, aud, iat, exp
      * Signed RS256 with RSA signing key (key id in header for rotation).
      * TTL = app.jwt.access-ttl-minutes (default 15 min).

Step 7: Issue / rotate refresh token
  - refreshTokens.issue(userId, userAgent, deviceId):
      * Generates 64 random bytes → Base64url string (opaque to client).
      * SQL: INSERT INTO refresh_tokens (user_id, token_hash, device_label,
        user_agent, expires_at) VALUES (..., SHA256(token), ?, ?, NOW() + 7d).
      * The stored value is the **hash**, so a DB leak does not expose live tokens.
      * A per-(userId, deviceId) slot is overwritten on rotation (single active
        session per device), which is what makes "logout elsewhere" work.

Step 8: Return response
  - HTTP 200 OK
  - Body: {token, customerId, fullName, role, refreshToken}  (see 3.2.2).
  - Client stores the access token in memory and the refresh token in secure
    storage; both go in subsequent requests:
      Authorization: Bearer <access-jwt>
    and refresh calls send {refreshToken: <opaque>} in the JSON body.

Step 9: Session established
  - The access JWT is stateless and verified locally by PlatformJwtAuthFilter
    on every subsequent request (signature via JWKS, expiry, aud/iss checks).
  - The refresh token is stateful and server-validated on each /auth/refresh.
```

#### 3.2.5 Database tables touched

| Table | Operation | Purpose |
|-------|-----------|---------|
| `customers` | SELECT | Look up by email + active check |
| `users` | SELECT (via FK) | Read role/active for scope + MFA flag |
| `admins` | SELECT | Admin credential fallback |
| `password_reset_tokens` | (none) | Not touched here |
| `refresh_tokens` | INSERT | Persist new rotating refresh token (hashed) |

#### 3.2.6 Caching behavior

- **None for login itself** (it is a read + write, and credentials must be authoritative).
- **JWKS** (`GET /.well-known/jwks.json`) *is* cached: public keys are cached for `JWKS_CACHE_TTL_SECONDS` (default 60s) to avoid re-fetching keys on every request verification.

#### 3.2.7 Security considerations

- **Account enumeration**: identical error message + identical timing posture for "no such user" vs "bad password" (BCrypt is deliberately constant work; a missing user still returns 401 without an early cheap reject).
- **Brute-force lockout**: per-(email, IP) exponential backoff → 429 + `Retry-After`.
- **Password hashing**: BCrypt strength 10 (~100ms) — never return stored hashes.
- **Refresh token rotation**: a refresh token is single-use per device; reuse of a rotated token triggers family revocation (defense against replay).
- **MFA**: TOTP required when enabled; `totpCode` never logged.
- **No admin self-registration**: `normalizeRole` rejects `ADMIN`; admin logins use seeded rows.

#### 3.2.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 400 Bad Request | Validation failure | Missing email, malformed |
| 401 Unauthorized | Bad credentials / TOTP missing | Wrong password, expired refresh token |
| 429 Too Many Requests | Per-IP or per-(email,IP) lockout | >30 logins/IP/5min, or >5 failed attempts |
| 500 Internal Server Error | DB or Redis unreachable | Connection pool exhausted |

---



## 4. Restaurant Service

**Port**: 8091  
**Database**: `restaurant_db` (PostgreSQL + PostGIS)  
**Pattern**: Public browse + owner self-service + menu management

### 4.1 GET /api/v1/restaurants/public

**Purpose**: List all active restaurants with pagination. This is the main discovery endpoint for customers.

#### 4.1.1 Request format
```
GET /api/v1/restaurants/public?page=0&size=20&cuisineId=5&isPureVeg=true&ids=1,2,3
```

#### 4.1.2 Response format
```json
{
  "items": [
    {
      "id": 1,
      "name": "Curry House",
      "description": "Authentic Indian cuisine",
      "cuisineId": 5,
      "cuisineName": "Indian",
      "address": {"line1": "123 Main St", "city": "Bangalore", ...},
      "rating": 4.3,
      "deliveryFee": 30.0,
      "minimumOrderAmount": 100.0,
      "isPureVeg": false,
      "isOpen": true,
      "latitude": 12.9716,
      "longitude": 77.5946
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "hasNext": true
}
```

#### 4.1.3 Controller code (real)
```java
@GetMapping("/api/v1/restaurants/public")
@Transactional(readOnly = true)
public Map<String, Object> listPublic(
        @RequestParam(required = false) String page,
        @RequestParam(required = false) String size,
        @RequestParam(required = false) Long cuisineId,
        @RequestParam(required = false) Boolean isPureVeg,
        @RequestParam(required = false) String ids) {
    
    // Step 1: Parse batch IDs (if provided)
    List<Long> idList = parseIds(ids);
    if (idList != null) {
        if (idList.size() > MAX_BATCH_IDS) {  // MAX_BATCH_IDS = 100
            throw new BusinessException("Too many ids; maximum is " + MAX_BATCH_IDS);
        }
        List<Restaurant> restaurants = idList.isEmpty()
                ? List.of()
                : restaurantRepository.findAllById(idList).stream()
                        .filter(r -> Boolean.TRUE.equals(r.getIsActive()))
                        .toList();
        return envelope(restaurants.stream().map(this::toMap).toList(), 0,
                restaurants.size(), restaurants.size());
    }
    
    // Step 2: Parse and clamp pagination
    int safePage = clamp(parseLong(page, 0), 0, 100_000);
    int safeSize = clamp(parseLong(size, 20), 1, MAX_PAGE_SIZE);  // MAX_PAGE_SIZE = 100
    
    // Step 3: Build pageable
    Pageable pageable = PageRequest.of(safePage, safeSize);
    
    // Step 4: Query database (read-only transaction)
    var result = restaurantRepository.findByIsActive(true, pageable);
    
    // Step 5: Map to lightweight DTO (exclude lazy collections)
    List<?> content = result.getContent().stream()
        .map(this::toMap)  // Converts Restaurant → Map, excluding menus/reviews
        .toList();
    
    // Step 6: Return envelope
    return envelope(content, result.getNumber(), result.getTotalElements(), result.getTotalPages());
}
```

#### 4.1.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/restaurants/public?page=0&size=20
  - No authentication required (public endpoint)

Step 2: Gateway routing
  - Path: /api/v1/restaurants/public
  - Matches "restaurant" route → forwards to http://restaurant:8091

Step 3: Spring MVC request mapping
  - PublicBrowseController.listPublic()
  - @Transactional(readOnly = true) begins
    * Hints to Hibernate: no dirty checking, no flush needed
    * PostgreSQL: transaction isolation level READ COMMITTED

Step 4: Parameter parsing and validation
  - page = "0" → parseLong("0", 0) → 0 → clamp(0, 0, 100000) → 0
  - size = "20" → parseLong("20", 20) → 20 → clamp(20, 1, 100) → 20
  - cuisineId = null (not provided)
  - isPureVeg = null (not provided)
  - ids = null (not provided)

Step 5: Repository query (Spring Data JPA)
  - restaurantRepository.findByIsActive(true, PageRequest.of(0, 20))
  - Generated SQL:
    SELECT * FROM restaurants 
    WHERE is_active = true 
    ORDER BY id ASC 
    LIMIT 20 OFFSET 0
  - PostgreSQL query planner: uses index idx_restaurants_is_active (if exists)
  - Returns: Page<Restaurant> with 20 entities

Step 6: Entity → DTO mapping
  - toMap(Restaurant r):
    * Include: id, name, description, cuisineId, address, rating, etc.
    * EXCLUDE: menus (lazy collection), reviews (lazy collection)
    * Why exclude? Loading menus would trigger N+1 queries (1 query for restaurants + N queries for menus)
  - Result: List<Map<String, Object>> with 20 lightweight maps

Step 7: Envelope construction
  - envelope(items, page, totalElements, totalPages):
    return Map.of(
        "items", items,
        "page", page,
        "size", size,
        "totalElements", totalElements,
        "hasNext", page < totalPages - 1
    );

Step 8: Transaction commit
  - @Transactional(readOnly = true) → no changes to commit
  - Transaction ends, connection returned to HikariCP pool

Step 9: JSON serialization
  - Spring MVC uses Jackson to convert Map → JSON
  - Content-Type: application/json

Step 10: Response sent
  - HTTP 200 OK
  - Body: {"items":[...],"page":0,"size":20,"totalElements":150,"hasNext":true}

Step 11: Optional cache population
  - If RedisCacheService is configured:
    cache.put("restaurants:public:page:0:size:20", response, 60 seconds)
  - Next request for same page hits cache (no DB query)
```

#### 4.1.5 Database impact

| Table | Query | Index |
|-------|-------|-------|
| `restaurants` | `SELECT * FROM restaurants WHERE is_active = true ORDER BY id ASC LIMIT 20 OFFSET 0` | `idx_restaurants_is_active` (btree on `is_active`) |

**N+1 prevention**: The `toMap()` method intentionally excludes lazy-loaded collections (`menus`, `reviews`). If we included them, Hibernate would issue additional queries for each restaurant's menu items.

#### 4.1.6 Caching behavior

- **Cache key**: `restaurants:public:page:{page}:size:{size}:cuisine:{cuisineId}:veg:{isPureVeg}`
- **TTL**: 60 seconds
- **Eviction**: On restaurant create/update/delete → `evictPattern("restaurants:public:*")`
- **Cache hit ratio target**: > 70% for public browse endpoints

#### 4.1.7 Security

- **Public endpoint**: No authentication required.
- **Input validation**: page/size are parsed as longs, clamped to safe bounds.
- **Batch ID limit**: Max 100 IDs per request to prevent abuse.

#### 4.1.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 400 Bad Request | Invalid page/size (non-numeric) | `?page=abc` → defaults to page=0 |
| 500 Internal Server Error | Database connection lost | PostgreSQL restart mid-request |

---

### 4.2 GET /api/v1/restaurants/public/nearby

**Purpose**: Discover restaurants within a radius of a latitude/longitude. This is the geo-discovery entry point for the "find food near me" flow. It is fully unauthenticated and geo-filtered in-JVM after a single active-restaurant fetch.

#### 4.2.1 Request format
```
GET /api/v1/restaurants/public/nearby?latitude=12.9716&longitude=77.5946&radiusKm=5
```
- `latitude` / `longitude` are **required** strings parsed to doubles.
- `radiusKm` is optional (default `5`); clamped to `[0.5, 50.0]` — never more than ~50 km is honored.

#### 4.2.2 Response format
Same envelope shape as 4.1.2, where each item is the lightweight restaurant map (no lazy menus/reviews), ordered from nearest to farthest, capped at 100 results:
```json
{
  "items": [{ "id": 1, "name": "Curry House", "latitude": 12.97, "longitude": 77.59, "rating": 4.3, ... }],
  "page": 0,
  "size": 100,
  "totalElements": 100,
  "hasNext": false
}
```

#### 4.2.3 Controller code (real)
```java
@GetMapping("/api/v1/restaurants/public/nearby")
@Transactional(readOnly = true)
public Map<String, Object> nearby(@RequestParam String latitude,
                                  @RequestParam String longitude,
                                  @RequestParam(defaultValue = "5") String radiusKm) {
    double lat = requireNumber(latitude, "latitude");
    double lng = requireNumber(longitude, "longitude");
    double radius = Math.max(0.5, Math.min(50.0, requireNumber(radiusKm, "radiusKm")));
    List<Restaurant> candidates = restaurantRepository.findByIsActiveTrue().stream()
            .filter(r -> r.getLatitude() != null && r.getLongitude() != null)
            .toList();
    List<Restaurant> near = candidates.stream()
            .filter(r -> haversineKm(lat, lng, r.getLatitude(), r.getLongitude()) <= radius)
            .sorted((a, b) -> Double.compare(
                    haversineKm(lat, lng, a.getLatitude(), a.getLongitude()),
                    haversineKm(lat, lng, b.getLatitude(), b.getLongitude())))
            .limit(MAX_BATCH_IDS)
            .toList();
    return envelope(near.stream().map(this::toMap).toList(), 0, near.size(), near.size());
}
```

#### 4.2.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/restaurants/public/nearby?latitude=12.9716&longitude=77.5946&radiusKm=5
  - No authentication (public endpoint).

Step 2: Gateway routing
  - Path "/api/v1/restaurants/**" → "restaurant" route → http://restaurant:8091.
  - EdgeRateLimitFilter + circuit breaker apply as in §2.2.

Step 3: Spring MVC request mapping
  - PublicBrowseController.nearby(lat, lng, radiusKm)
  - @Transactional(readOnly = true) begins (READ COMMITTED, no dirty checking).

Step 4: Parameter parsing & bounds clamp
  - latitude  "12.9716" → requireNumber → 12.9716  (invalid → BusinessException → 400)
  - longitude "77.5946" → 77.5946
  - radiusKm  "5"       → parseLong→5.0 → clamp(5.0, 0.5, 50.0) → 5.0

Step 5: Candidate fetch (single SQL, NOT per-restaurant)
  - restaurantRepository.findByIsActiveTrue()
  - SQL: SELECT id, name, latitude, longitude, rating, is_active FROM restaurants WHERE is_active = true
  - This loads ALL active restaurants once (the table is small enough that the
    full scan is acceptable; there is an index idx_restaurants_is_active).
  - Filter to those with non-null coordinates (in-memory).

Step 6: In-JVM geo filtering (great-circle / haversine)
  - haversineKm(lat, lng, r.lat, r.lng) computes the great-circle distance.
  - Retained only if distance <= radius (5 km).
  - NOTE: This is NOT PostGIS-driven because the nearby endpoint must return
    exact haversine distances for sorting; the PostGIS `geog` index is used by
    the admin/owner-side near-operator queries, not this public path.

Step 7: Sort + cap
  - Sorted ascending by distance (nearest first).
  - .limit(100) caps the result (MAX_BATCH_IDS) to bound response size.

Step 8: Entity → lightweight DTO mapping (toMap)
  - Excludes lazy collections (menus, reviews) to avoid N+1.
  - Includes distance-bearing fields (latitude, longitude) for the client.

Step 9: Envelope + transaction end
  - envelope(items, 0, size, size) builds the standard page wrapper.
  - @Transactional(readOnly=true) ends; connection returned to HikariCP.

Step 10: JSON serialization + response
  - Jackson serializes Map → JSON.
  - HTTP 200 OK with items ordered nearest→farthest.
```

#### 4.2.5 Database impact

| Table | Query | Index |
|-------|-------|-------|
| `restaurants` | `SELECT ... FROM restaurants WHERE is_active = true` | `idx_restaurants_is_active` |

Because filtering/sorting happens in-JVM, the SQL is a single full-scan of active rows; the bounding box for very large regions is left to the caller's radius clamp.

#### 4.2.6 Caching considerations

- **Not cached today** (geospatial queries are low-repetition and user-location-dependent).
- The `restaurant-nearby` cache constant exists in `CacheConstants` for a future geo-tile cache; no route currently populates it. If added later, the eviction key would be `evictPattern("restaurants:public:nearby:*")` keyed by rounded geo-hash tiles.

#### 4.2.7 Security

- Public, no auth.
- `radiusKm` clamped server-side to `[0.5, 50.0]` (prevents a huge radius from forcing a massive in-memory scan).
- `latitude`/`longitude` are double-parsed with bounds enforcement.

#### 4.2.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 400 Bad Request | Non-numeric/coordinates out of range | `?latitude=abc` or `?latitude=120` |
| 500 Internal Server Error | DB unreachable | PostgreSQL restart mid-scan |

---

### 4.3 GET /api/v1/menu/items (Batch price resolution)

**Purpose**: Resolve the current name + price for a set of menu-item IDs in a single round-trip. This is the **server-side price source of truth** that the order and cart flows call before any money is recorded, so a client can never dictate a price.

#### 4.3.1 Request format
```
GET /api/v1/menu/items?ids=456,789
```
- `ids` is optional. If absent or empty, the endpoint returns `{"items":[]}`.
- The list is capped at `MAX_BATCH_IDS = 100`; exceeding it throws `BusinessException` → 400.

#### 4.3.2 Response format
```json
{
  "items": [
    { "id": 456, "restaurantId": 123, "name": "Paneer Tikka", "price": 199.00,
      "isActive": true, "isAvailable": true }
  ]
}
```

#### 4.3.3 Controller code (real)
```java
@GetMapping("/api/v1/menu/items")
@Transactional(readOnly = true)
public Map<String, Object> batchMenuItems(@RequestParam(required = false) String ids) {
    List<Long> idList = parseIds(ids);
    if (idList == null || idList.isEmpty()) {
        return Map.of("items", List.of());
    }
    if (idList.size() > MAX_BATCH_IDS) {
        throw new BusinessException("Too many ids; maximum is " + MAX_BATCH_IDS);
    }
    // PERF-3: per-id cache (60s) keyed "menu:item:<id>"; cold ids load from DB.
    List<MenuItem> items = new ArrayList<>();
    List<Long> cold = new ArrayList<>();
    for (Long id : idList) {
        MenuItem cached = menuCacheGet(id);      // L1/L2 cache
        if (cached != null) items.add(cached);
        else cold.add(id);
    }
    if (!cold.isEmpty()) {
        menuItemRepository.findAllById(cold).forEach(items::add);
    }
    List<Map<String,Object>> rendered = items.stream()
            .map(this::menuItemToMap).toList();
    return Map.of("items", rendered);
}
```

#### 4.3.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/menu/items?ids=456,789 (no auth required; called server-to-server)
  - Gateway routes /api/v1/menu/** → restaurant service.

Step 2: Cache lookup (PERF-3 chord)
  - For each requested id, check per-id cache key "menu:item:<id>" (60s TTL).
  - Cache HIT → returned directly (no DB).
  - Cache MISS → the id is added to a "cold" list for a single batched DB load.

Step 3: Batched DB load (only for cold ids)
  - menuItemRepository.findAllById(cold) → a single SELECT ... WHERE id IN (...).
  - SQL: SELECT id, restaurant_id, name, price, is_active, is_available
         FROM menu_items WHERE id IN (?, ?)

Step 4: Render + populate cache
  - Each cold item is placed into the per-id cache for subsequent requests.
  - Money decimals are rendered as BigDecimal (USE_BIG_DECIMAL_FOR_FLOATS)
    so a cached item is byte-identical to a freshly loaded one.

Step 5: Return envelope
  - {"items": [ {id, restaurantId, name, price, isActive, isAvailable}, ... ]}
  - Order of items is not guaranteed to match the request id order.

Step 6: Caller (order/cart) consumes prices
  - OrderService.rePrice() / CartController.addItem() use these authoritative
    prices; any client-supplied unitPrice is discarded. A missing id among the
    responses rejects the whole order/cart add (fail-closed).
```

#### 4.3.5 Database impact

| Table | Query | Index |
|-------|-------|-------|
| `menu_items` | `SELECT ... WHERE id IN (...)` | `PRIMARY KEY (id)` |

#### 4.3.6 Caching behavior

- **Per-id cache**: key `menu:item:<id>`, TTL 60s, two-tier (Caffeine L1 → Redis L2).
- **Invalidate on change**: when a restaurant updates a menu item (via the owner surface), the item cache is evicted so the next price resolution reflects the change.

#### 4.3.7 Security

- Public (unauthenticated) because prices must be resolvable for the checkout chord.
- No PII in the response.
- Batch size capped at 100 to bound query width.

#### 4.3.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 400 Bad Request | Too many ids | `ids=1,2,...,101` |
| 400 Bad Request | Non-numeric id | `ids=abc` |
| 503 | Restaurant DB unavailable | connection refused |

---



## 5. Order Service

**Port**: 8092  
**Database**: `order_db` (PostgreSQL)  
**Pattern**: Saga-based order creation with compensation, outbox events

### 5.1 POST /api/v1/orders (Customer Order Creation)

**Purpose**: Customer places an order. This is the most complex API in the system because it coordinates multiple services via a saga.

#### 5.1.1 Request format
```json
{
  "restaurantId": 123,
  "items": [
    {
      "menuItemId": 456,
      "quantity": 2
    }
  ]
}
```

#### 5.1.2 Response format (success)
```json
{
  "id": 789,
  "customerId": 42,
  "restaurantId": 123,
  "status": "CONFIRMED",
  "total": 598.00,
  "paymentMethod": "WALLET",
  "deliveryAddressId": 100,
  "createdAt": "2026-09-16T13:00:00Z",
  "items": [
    {
      "id": 1,
      "menuItemId": 456,
      "name": "Paneer Tikka",
      "price": 199.00,
      "quantity": 2,
      "subtotal": 398.00
    }
  ]
}
```

#### 5.1.3 Controller code (real — `CustomerOrderController`)
```java
@PostMapping
public OrderResponse create(@AuthenticationPrincipal TokenPrincipal principal,
                            @PathVariable Long customerId,
                            @RequestBody CreateOrderRequest request) {
    requireSelfOrAdmin(principal, customerId);            // IDOR guard (see 5.1.7)
    if (request == null || request.restaurantId() == null) {
        throw new BusinessException("restaurantId is required");
    }
    if (request.customerId() != null && !request.customerId().equals(customerId)) {
        throw new BusinessException("customerId mismatch between path and body");
    }
    // Path identity wins over any body-supplied customerId (IDOR guard).
    return orderService.createOrder(
            new CreateOrderRequest(customerId, request.restaurantId(), request.items()));
}
```
The route is `POST /api/v1/customers/{customerId}/orders` (path-nested under the customer), so the order's owner is taken **from the URL path**, not the request body. `requireSelfOrAdmin` throws `AccessDeniedException` → 403 if a non-admin tries to order for another customer.

**Key security insight**: The controller **ignores** any `customerId` in the request body. It uses the path `customerId` — which itself is bound to the JWT subject by `requireSelfOrAdmin`. This prevents a malicious client from placing orders on behalf of other users.

#### 5.1.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - POST /api/v1/orders
  - Headers: Authorization: Bearer <customer_jwt>
  - Body: {"restaurantId": 123, "items": [{"menuItemId": 456, "quantity": 2}]}

Step 2: Gateway routing
  - Path: /api/v1/orders/**
  - Matches "order" route → forwards to http://order-service:8092

Step 3: JWT validation (PlatformJwtAuthFilter)
  - Extracts token from Authorization header
  - Verifies RS256 signature via the JWKS endpoint / local RSA keypair
    (legacy HS256 accepted only during the cutover grace window)
  - Checks expiration (exp claim) + issuer/audience
  - Parses claims: sub="42", scope="CUSTOMER", role="CUSTOMER", typ="access"
  - Creates TokenPrincipal(userId=42, role="CUSTOMER", scope="CUSTOMER")
  - Sets principal in SecurityContext

Step 4: Controller method execution
  - CustomerOrderController.create(principal, customerId=42, request)
  - @Valid triggers validation on the body (restaurantId @NotNull, items @NotEmpty)
  - requireSelfOrAdmin(principal, 42) → admin or subject==42, else 403
  - Rejects body/customerId mismatch → 400
  - Builds CreateOrderRequest(42, 123, items) — customerId from PATH (JWT-bound)

Step 5: OrderService.createOrder() — PRICE RESOLUTION (server-side of truth)
   - @Transactional begins (writes order + items + saga/outbox in one tx).
   - rePrice(request) funnels through RestaurantPricedItemResolver.resolveAll(...)
     * Single batched call to restaurant service: GET /api/v1/menu/items?ids=456,789
       (see §4.3 — each id is resolved via the 60s per-id cache; cold ids hit the DB).
     * Returns authoritative {name, price} for each id.
   - Money-integrity rule: client-supplied unitPrice/name are NEVER trusted.
     A menu id not present in the restaurant's response → BusinessException
     ("Menu item is temporarily unavailable") → 400. A restaurant outage →
     UpstreamUnavailableException → 503. Fail-closed: no fallback to client prices.
   - Calculates line totals + order total (BigDecimal, scale 2, HALF_UP):
     2 × 199.00 = 398.00

Step 6: Build + persist Order entity (inside @Transactional)
  - Order order = new Order()
  - order.setCustomerId(42)        // from JWT-bound path
  - order.setRestaurantId(123)
  - order.setStatus(Order.STATUS_CREATED)   // NOT "PENDING" — CREATED is the
                                             // initial write state before saga
  - order.setTotalAmount(398.00)
  - order.setCurrency(...)        // default "INR"
  - orderRepository.save(order) → INSERT INTO orders (id=789 generated)
  - No delivery address resolution at create time; address is snapshotted
    later when the customer confirms checkout.

Step 7: Build OrderItem entities (server-priced, inside @Transactional)
  - For each re-priced line:
    OrderItem item = new OrderItem()
    item.setOrderId(789)
    item.setMenuItemId(456)            // from client (validated against menu)
    item.setItemName("Paneer Tikka")  // from server-resolved price (not client)
    item.setUnitPrice(199.00)         // authoritative price (not client-supplied)
    item.setQuantity(2)
    item.setSubtotal(398.00)          // unitPrice × quantity
    orderItemRepository.save(item)
  - Subtotal is derived, never trusted from the client.

Step 8: Persist order + items atomically (still in @Transactional)
  - INSERT INTO orders (...) VALUES (...);           → id = 789
  - INSERT INTO order_items (order_id, ...) VALUES (789, ...);  (1 row per line)
  - These commit together (ACID): a crash after this tx leaves a real order
    row and a real outbox/event trail to compensate against.

Step 9: Run the ORDER_CREATION saga (default: SYNCHRONOUS in the request thread)
  - serviceToken() builds a service JWT (ServiceJwtAuthTokenProvider).
  - When app.order.async-saga.enabled = false (default):
      SagaInstance saga = sagaCoordinator.executeSaga(
          SAGA_TYPE="ORDER_CREATION", "789", payload,
          List.of(RESERVE_STOCK step, CHARGE_PAYMENT step))
    → executeSaga BLOCKS the request thread until both steps finish. Each step
      is a synchronous RPC (restaurant + payment) with a 20s outer timeout
      (SAGA_RPC_TIMEOUT) and its own retry/timeout on the WebClient.
  - When async-saga = true: RESERVE_STOCK still runs inline; on success the
      order flips to AWAITING_PAYMENT and a `payment_requested` outbox event
      drives the charge asynchronously from the payment service (W1-MONEY).
  - Client receives the FINAL order status in the response (not an eventual one).

Step 10: Saga Step 1 — RESERVE_STOCK (synchronous by default)
  - RestaurantClient.reserveStock(reservationLines, serviceToken)
  - HTTP POST to restaurant service: /api/v1/inventory/stock-reservation/reserve
    * Header: X-Service-Token: <service_jwt>  (service-to-service token, not a bearer access JWT)
    * Body: [{menuItemId, menuItemName, quantity}]
  - Restaurant service:
    a) ServiceJwtAuthFilter validates X-Service-Token → ROLE_SERVICE
    b) Checks active stock (is_available, stock_quantity)
    c) Reserves (deducts) stock: UPDATE menu_items SET stock_quantity = stock_quantity - 2 WHERE id = 456
       (reservation, not permanent deduction — released on compensation)
    d) Returns 2xx with reserved lines
  - Saga step result: SUCCESS → continue to CHARGE_PAYMENT
    FAILURE → jump to Step 14 compensation (no payment attempted)

Step 11: Saga Step 2 — CHARGE_PAYMENT (synchronous by default)
  - Only runs if RESERVE_STOCK succeeded.
  - PaymentServiceClient.charge(orderId=789, customerId=42, amount=398.00,
        paymentMethod="WALLET", reference="ORDER-789", serviceToken)
  - HTTP POST to payment service: /api/v1/internal/payments/charge
    * Header: X-Service-Token: <service_jwt>
    * Body: {orderId:789, customerId:42, amount:"398.00", paymentMethod:"WALLET", reference:"ORDER-789"}
  - Payment service (InternalPaymentController.charge):
    a) @PreAuthorize hasAnyRole('SERVICE','ADMIN') — service JWT required
    b) Idempotency: an idempotency key (here derived from reference "ORDER-789")
       is checked; a prior COMPLETED charge returns the existing payment id
       instead of re-charging.
    c) Creates Payment (status=PENDING), then CHARGED
    d) For WALLET: debits wallet → UPDATE wallet_balances SET balance = balance - 398.00
       WHERE customer_id = 42 (+ wallet_transactions audit row)
    e) Returns ChargeResponse(paymentId=123, status="CHARGED")
  - Saga step result: SUCCESS → order CONFIRMED
    FAILURE/timeout/null → compensation (refund if partially charged, release stock)

Step 12: Saga completion — set final order status
  - If saga.getStatus() == COMPLETED:
    order.setStatus(STATUS_CONFIRMED); orderRepository.save(order)
  - If saga unwound/failed:
    order.setStatus(STATUS_CANCELLED); orderRepository.save(order)

Step 13: Emit domain events (outbox, SAME @Transactional — atomic with the status write)
  - eventPublisher.orderCreated(orderId, customerId, restaurantId)   // always
  - If CONFIRMED: orderStatusChanged(orderId, CONFIRMED)
                  orderItemsSnapshot(orderId, restaurantId, snapshotItems)
  - If CANCELLED: orderStatusChanged(orderId, CANCELLED)
  - These write outbox_events rows (status=PENDING) in the same transaction,
    so they survive a crash; the OutboxPollPublisher (§9.4) drains them to Kafka.

Step 14: Saga Failure compensation (runs inside executeSaga before returning)
  - If RESERVE_STOCK failed:
    a) Order status → CANCELLED (no stock was reserved, no payment)
    b) Timeline event: "Order cancelled — stock unavailable"
  - If CHARGE_PAYMENT failed AFTER stock reserved:
    a) Order status → CANCELLED
    b) Compensation 1: refundCharge(...) → POST /api/v1/internal/payments/{id}/refund
       (only fires if a payment id was obtained)
    c) Compensation 2: releaseReservations(...) → POST /api/v1/inventory/stock-reservation/release
    d) If a wallet debit partially happened, it is reversed here.
  - Compensation steps are the lambda registered on each SagaStepDefinition;
    the saga engine runs them in REVERSE order of the successful steps.
```

#### 5.1.5 Database tables touched

| Table | Operation | When |
|-------|-----------|------|
| `orders` | INSERT | Step 8 (order creation) |
| `order_items` | INSERT (one per line) | Step 8 (order items) |
| `saga_instances` | INSERT | Step 9 (saga start, by SagaCoordinator) |
| `saga_steps` | INSERT (per step) | Steps 10-11 (RESERVE_STOCK, CHARGE_PAYMENT) |
| `outbox_events` | INSERT (multiple) | Step 13 (OrderCreated, OrderStatusChanged, OrderItemsSnapshot) |
| `wallet_balances` | UPDATE (debit) | Step 11 — inside payment service DB |
| `wallet_transactions` | INSERT | Step 11 — inside payment service DB |
| `menu_items` | UPDATE (stock reservation) | Step 10 — inside restaurant service DB |

#### 5.1.6 Transaction boundaries

```
Request thread (default, synchronous saga):
  @Transactional            ← one DB transaction spans steps 8–13
    ├─► INSERT orders
    ├─► INSERT order_items
    ├─► SagaCoordinator.executeSaga → INSERT saga_instances, saga_steps
    │        ├─► RESERVE_STOCK RPC → restaurant service (debit stock)
    │        └─► CHARGE_PAYMENT RPC → payment service (debit wallet, INSERT payment)
    ├─► UPDATE orders (status = CONFIRMED or CANCELLED)
    ├─► recordTimeline (INSERT timeline event)
    ├─► INSERT outbox_events (OrderCreated + OrderStatusChanged + snapshot)
    └─► COMMIT
  ↓
  Return OrderResponse (FINAL status already known to the client)

Async-saga mode (app.order.async-saga.enabled=true):
  @Transactional
    ├─► INSERT orders (status = AWAITING_PAYMENT)
    ├─► INSERT outbox_events (...payment_requested...)
    └─► COMMIT
  ↓
  Payment service consumes payment_requested → charges wallet →
    publishes payment_settled → OrderSagaEventConsumer → CONFIRMED/CANCELLED
```

**Why synchronous by default?** The saga RPCs (stock reserve + wallet charge) are local-cluster calls with a 20s outer timeout, so blocking the request thread keeps the client on a single consistent response. Async mode exists only for the payment hand-off when the PSP path must be decoupled from the order service's transaction.

#### 5.1.7 Caching behavior

- **Order cache**: Redis key `order:{id}`, TTL 30 seconds
- **Customer orders cache**: Redis key `orders:customer:{customerId}:page:{page}`, TTL 60 seconds
- **Cache invalidation**: On order status change → `evict("order:" + orderId)` and `deletePattern("orders:customer:{customerId}:*")` (in `OrderService.invalidateOrderCache`)

#### 5.1.8 Service-to-service calls

| Call | Service | Endpoint | Auth header | Timeout |
|------|---------|----------|-------------|---------|
| Reserve stock | Restaurant | `POST /api/v1/inventory/stock-reservation/reserve` | `X-Service-Token` | 20s |
| Release stock (compensation) | Restaurant | `POST /api/v1/inventory/stock-reservation/release` | `X-Service-Token` | 20s |
| Charge payment | Payment | `POST /api/v1/internal/payments/charge` | `X-Service-Token` | 20s |
| Refund (compensation) | Payment | `POST /api/v1/internal/payments/{id}/refund` | `X-Service-Token` | 20s |

**Service token**: Generated by `ServiceJwtAuthTokenProvider`, signed with `app.auth.service.jwt-secret`, carries `ROLE_SERVICE` authority. Sent via the `X-Service-Token` header (not `Authorization: Bearer`). Validated by `ServiceJwtAuthFilter` on every `/api/v1/internal/**` endpoint.

**Why X-Service-Token?** The access JWT (RS256, 15-min TTL) is for end-users. Internal mesh calls use a separate, longer-lived service token so the order saga can talk to restaurant/payment without a user context.

#### 5.1.9 Error handling

| Failure | Compensation | Client sees |
|---------|--------------|-------------|
| Restaurant not found | Order cancelled immediately | 404 (validation error) |
| Stock unavailable | Order cancelled, no payment | 400 "Item out of stock" |
| Payment declined | Stock released, order cancelled | 400 "Payment failed" |
| Network timeout (restaurant) | Retry 3x, then cancel | 500 → saga retries |
| Network timeout (payment) | Retry 3x, then cancel + refund | 500 → saga retries |

#### 5.1.10 Common pitfalls (for interns)

1. **Synchronous saga by default**: `SagaCoordinator.executeSaga` blocks the request thread until both steps finish. Do NOT add a `Thread.sleep` or slow logic inside a step — it holds the DB transaction open for the duration. Use `app.order.async-saga.enabled=true` only if you genuinely need to decouple payment from the order tx.
2. **Never trust client-provided customerId**: The path `customerId` is bound to the JWT subject by `requireSelfOrAdmin`. Any body-supplied `customerId` is explicitly rejected if it differs from the path.
3. **Stock is reserved, not deducted**: Stock is only permanently deducted on CONFIRMED. If the saga fails, `releaseReservations` runs compensation. A crash mid-compensation can leave a stuck PENDING step — the admin/ops path must reconcile.
4. **Idempotency**: The payment charge endpoint is idempotent by `reference` (derived from `ORDER-{orderId}`). Retries of the whole order create a new order + new saga; the underlying charge is deduplicated by reference inside the payment service.
5. **Outbox is in the same tx**: If the DB commit succeeds but Kafka is down, the outbox row stays PENDING and the OutboxPollPublisher retries. Never swallow the outbox write.

---

### 5.2 GET /api/v1/customers/{customerId}/orders (List order history)

**Purpose**: A customer (or admin) views their paginated order history. Each order is returned with its current status and items.

#### 5.2.1 Request format
```
GET /api/v1/customers/42/orders?page=0&size=20
```

#### 5.2.2 Response format
```json
{
  "items": [
    { "id": 789, "restaurantId": 123, "status": "CONFIRMED",
      "totalAmount": 398.00, "items": [...], "createdAt": "..." }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 15,
  "hasNext": true
}
```

#### 5.2.3 Controller code (real — `CustomerOrderController`)
```java
@GetMapping
public Map<String, Object> myOrders(@AuthenticationPrincipal TokenPrincipal principal,
                                        @PathVariable Long customerId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
    requireSelfOrAdmin(principal, customerId);
    Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(size, 100), Sort.Direction.DESC, "id");
    Page<OrderResponse> result = orderService.getOrdersForCustomer(customerId, pageable);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", result.getContent());
    body.put("page", result.getNumber());
    body.put("size", result.getSize());
    body.put("totalElements", result.getTotalElements());
    body.put("hasNext", result.hasNext());
    return body;
}
```

#### 5.2.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/customers/42/orders?page=0&size=20
  - Headers: Authorization: Bearer <customer_jwt>

Step 2: Gateway routing
  - Path "/api/v1/customers/**" → "order" route → http://order-service:8092

Step 3: JWT validation
  - PlatformJwtAuthFilter extracts TokenPrincipal (RS256, see §5.1.4 Step 3).
  - scope = "CUSTOMER" (or "ADMIN").

Step 4: IDOR guard
  - requireSelfOrAdmin(principal, 42):
      * principal == null → AccessDeniedException → 403
      * principal.scope() == "ADMIN" → allow
      * principal.userId() == 42 → allow
      * otherwise → AccessDeniedException → 403
  - This prevents a customer from reading another customer's orders.

Step 5: Pagination parsing
  - page = 0 (clamped ≥ 0), size = 20 (clamped ≤ 100)
  - Sort: DESC by id (newest first)

Step 6: Service layer query
  - OrderService.getOrdersForCustomer(42, pageable)
  - orderRepository.findByCustomerId(42, pageable)
  - SQL: SELECT * FROM orders WHERE customer_id = ? ORDER BY id DESC LIMIT ? OFFSET ?
  - Page<OrderResponse> result = page.map(this::toResponse)

Step 7: Envelope + response
  - Build LinkedHashMap: {items, page, size, totalElements, hasNext}
  - Jackson serializes → JSON
  - HTTP 200 OK

Step 8: Cache note
  - The paginated endpoint does NOT use the Redis order cache (that cache is keyed per single order id). The list endpoint queries the DB directly to avoid stale page snapshots.
```

#### 5.2.5 Database impact

| Table | Query | Index |
|-------|-------|-------|
| `orders` | `SELECT ... WHERE customer_id = ? ORDER BY id DESC LIMIT ? OFFSET ?` | `idx_orders_customer_id` (or PK scan) |

#### 5.2.6 Security

- **IDOR guard**: `requireSelfOrAdmin` ensures a customer can only see their own orders; admins see all.
- **Pagination bounds**: size clamped to ≤ 100 to prevent oversized responses.

#### 5.2.7 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 403 Forbidden | Non-admin accessing another customer's orders | Customer A reads Customer B's orders |
| 401 Unauthorized | Missing/invalid JWT | No Authorization header |

---

### 5.3 POST /api/v1/customers/{customerId}/cart/items + DELETE /api/v1/customers/{customerId}/cart

**Purpose**: Add an item to a customer's cart (with server-side price resolution) and clear the cart.

#### 5.3.1 POST — Add to cart

**Request format**
```
POST /api/v1/customers/42/cart/items
Headers: Authorization: Bearer <customer_jwt>
Body: {"menuItemId": 456, "quantity": 2}
```

**Response format** (200 OK)
```json
{ "id": 1, "customerId": 42, "menuItemId": 456,
  "name": "Paneer Tikka", "price": 199.00, "quantity": 2,
  "subtotal": 398.00 }
```

**Controller code (real — `CartController`)**
```java
@PostMapping("/cart/items")
public Cart addItem(@AuthenticationPrincipal TokenPrincipal principal,
                      @PathVariable Long customerId,
                      @RequestBody AddItemRequest request) {
    PrincipalGuard.requireSelfOrAdmin(principal, customerId);
    if (request == null || request.menuItemId() == null) {
        throw new BusinessException("menuItemId is required");
    }
    if (request.quantity() <= 0) {
        throw new BusinessException("quantity must be positive");
    }
    // Server-side price resolution: ignore any client-provided name/price.
    RestaurantPricedItemResolver.PricedItem item =
            pricedItemResolver.resolve(request.menuItemId());
    return cartService.addItem(customerId, request.menuItemId(),
            item.name(), item.price(), request.quantity());
}
```

**Detailed E2E flow (POST)**

```
Step 1: HTTP Request
  - POST /api/v1/customers/42/cart/items
  - Body: {"menuItemId": 456, "quantity": 2}

Step 2: IDOR guard
  - requireSelfOrAdmin(principal, 42) → 403 if not self/admin

Step 3: Validation
  - request.menuItemId() == null → 400 "menuItemId is required"
  - request.quantity() <= 0 → 400 "quantity must be positive"

Step 4: Server-side price resolution
  - pricedItemResolver.resolve(456)
  - Calls restaurant service (cached batch endpoint, see §4.3)
  - Returns PricedItem {name: "Paneer Tikka", price: 199.00}
  - A missing/unavailable item → 400 (fail-closed; client price ignored)

Step 5: Cart service add
  - cartService.addItem(42, 456, "Paneer Tikka", 199.00, 2)
  - Upserts cart row (UNIQUE(customer_id, menu_item_id)):
      * If existing: quantity += 2
      * If new: INSERT cart row with name/price snapshot
  - Returns the Cart item

Step 6: Response
  - HTTP 200 OK with the Cart item (name/price from server, not client)
```

#### 5.3.2 DELETE — Clear cart

**Request format**
```
DELETE /api/v1/customers/42/cart
Headers: Authorization: Bearer <customer_jwt>
```

**Response**: HTTP 204 No Content (empty body)

**Controller code (real — `CartController`)**
```java
@DeleteMapping("/cart")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void clearCart(@AuthenticationPrincipal TokenPrincipal principal,
                        @PathVariable Long customerId) {
    PrincipalGuard.requireSelfOrAdmin(principal, customerId);
    cartService.clear(customerId);
}
```

**Detailed E2E flow (DELETE)**

```
Step 1: HTTP Request
  - DELETE /api/v1/customers/42/cart

Step 2: IDOR guard
  - requireSelfOrAdmin(principal, 42) → 403 if not self/admin

Step 3: Cart service clear
  - cartService.clear(42)
  - DELETE FROM cart_items WHERE customer_id = 42
  - (or DELETE FROM cart WHERE customer_id = 42 depending on schema)

Step 4: Response
  - HTTP 204 No Content (void return + @ResponseStatus)
  - No body
```

#### 5.3.3 Database impact

| Table | Operation | When |
|-------|-----------|------|
| `cart_items` | SELECT + INSERT/UPDATE | POST add (upsert) |
| `cart_items` | DELETE | DELETE clear |

#### 5.3.4 Security

- **IDOR guard**: Same `requireSelfOrAdmin` on both endpoints.
- **Server-side pricing**: Client cannot inject prices (POST).
- **204 on clear**: DELETE returns no content (idempotent — clearing an empty cart is still 204).

---

### 5.4 GET /api/v1/orders/restaurant/{restaurantId}/kitchen-queue

**Purpose**: Restaurant owner (or admin) views the active orders in their kitchen — orders that are CONFIRMED, PREPARING, or READY_FOR_PICKUP. This is the "what's cooking now" view for the merchant app.

#### 5.4.1 Request format
```
GET /api/v1/orders/restaurant/123/kitchen-queue?page=0&size=20
```

#### 5.4.2 Response format
```json
{
  "content": [
    { "id": 789, "customerId": 42, "status": "PREPARING",
      "totalAmount": 398.00, "items": [...], "createdAt": "..." }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 3,
  "hasNext": false
}
```

#### 5.4.3 Controller code (real — `OrderOpsController`)
```java
@GetMapping("/api/v1/orders/restaurant/{restaurantId}/kitchen-queue")
@Transactional(readOnly = true)
public Page<OrderResponse> kitchenQueue(@AuthenticationPrincipal TokenPrincipal principal,
                                            @PathVariable Long restaurantId,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
    requireRestaurantOwnerOrAdmin(principal, restaurantId);
    Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(size, 100), Sort.Direction.DESC, "id");
    List<String> statuses = List.of(
            Order.STATUS_CONFIRMED, Order.STATUS_PREPARING, Order.STATUS_READY_FOR_PICKUP);
    return orderRepository.findByRestaurantIdAndStatusIn(restaurantId, statuses, pageable)
            .map(orderService::toResponseCompat);
}
```

#### 5.4.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/orders/restaurant/123/kitchen-queue?page=0&size=20
  - Headers: Authorization: Bearer <owner_or_agent_jwt>

Step 2: Gateway routing
  - Path "/api/v1/orders/**" → "order" route → http://order-service:8092

Step 3: JWT validation
  - TokenPrincipal extracted. scope could be RESTAURANT_OWNER, DELIVERY_AGENT, or ADMIN.

Step 4: Ownership guard
  - requireRestaurantOwnerOrAdmin(principal, 123):
      * ADMIN → allow (admin sees all restaurants)
      * RESTAURANT_OWNER → load Restaurant by id 123, check ownerId == principal.userId()
      * DELIVERY_AGENT → DENIED (agents don't see kitchen queue)
      * Otherwise → AccessDeniedException → 403
  - If restaurant 123 doesn't exist → ResourceNotFoundException → 404

Step 5: Pagination parsing
  - page = 0 (clamped ≥ 0), size = 20 (clamped ≤ 100)
  - Sort: DESC by id

Step 6: Query active orders
  - orderRepository.findByRestaurantIdAndStatusIn(123, [CONFIRMED, PREPARING, READY_FOR_PICKUP], pageable)
  - SQL: SELECT * FROM orders WHERE restaurant_id = ? AND status IN (?, ?, ?) ORDER BY id DESC LIMIT ? OFFSET ?
  - Returns Page<Order>

Step 7: Map to response DTO
  - orderService::toResponseCompat converts Order → OrderResponse
  - Lightweight: no lazy collection loading

Step 8: Response
  - Page<OrderResponse> → Jackson serializes Spring's default Page JSON
    {content, page, size, totalElements, hasNext}
  - HTTP 200 OK
```

#### 5.4.5 Database impact

| Table | Query | Index |
|-------|-------|-------|
| `orders` | `SELECT ... WHERE restaurant_id = ? AND status IN (?, ?, ?) ORDER BY id DESC LIMIT ? OFFSET ?` | `idx_orders_restaurant_status` (composite) |

#### 5.4.6 Security

- **Ownership**: Only the restaurant owner or an admin can view this queue.
- **Status filter**: Hardcoded to the three "active kitchen" statuses — agents/customers don't see this endpoint.

#### 5.4.7 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 403 Forbidden | Agent or non-owner accessing the queue | Delivery agent reads restaurant queue |
| 404 Not Found | Restaurant doesn't exist | restaurantId = 9999 |
| 401 Unauthorized | Missing/invalid JWT | No Authorization header |

---



## 6. Payment Service

**Port**: 8093  
**Database**: `payment_db`  
**Pattern**: Idempotent payment charge with outbox, wallet operations

### 6.1 POST /api/v1/payments

**Purpose**: Process a payment for an order (or top up wallet).

#### 6.1.1 Request format
```json
{
  "orderId": 789,
  "amount": 299.00,
  "paymentMethod": "CASH_ON_DELIVERY"
}
```

**Required header**: `Idempotency-Key: unique-key-123`

#### 6.1.2 Response format
```json
{
  "paymentId": 123,
  "orderId": 789,
  "customerId": 42,
  "amount": 299.00,
  "method": "CASH_ON_DELIVERY",
  "status": "SETTLED",
  "providerRef": "pay_ABC123",
  "createdAt": "2026-09-16T13:00:00Z"
}
```

#### 6.1.3 Controller code (real)
```java
@PostMapping
public PaymentResponse pay(@AuthenticationPrincipal TokenPrincipal principal,
                           @Valid @RequestBody PaymentRequest request,
                           @RequestHeader("Idempotency-Key") String idempotencyKey) {
    // 1. Require authentication (any authenticated user)
    PrincipalGuard.requireAuthenticated(principal);
    
    // 2. Use JWT subject as payer (NOT request body)
    Long customerId = principal.userId();
    
    // 3. Delegate to service
    Payment payment = paymentService.processPayment(
        request.getOrderId(),
        customerId,
        request.getAmount(),
        request.getPaymentMethod(),
        idempotencyKey);
    
    // 4. Map to response DTO
    return paymentMapper.toPaymentResponse(payment);
}
```

#### 6.1.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - POST /api/v1/payments
  - Headers:
    * Authorization: Bearer <customer_jwt>
    * Idempotency-Key: order-789-payment-001
    * Content-Type: application/json
  - Body: {"orderId": 789, "amount": 299.00, "paymentMethod": "CASH_ON_DELIVERY"}

Step 2: Gateway routing
  - Path: /api/v1/payments/**
  - Matches "payment" route → forwards to http://payment-service:8093

Step 3: JWT validation
  - Same as order service: TokenPrincipal extracted

Step 4: Controller validation
  - @Valid on PaymentRequest:
    * @NotNull orderId
    * @NotNull @Positive amount
    * @NotBlank paymentMethod
  - Idempotency-Key extracted from header (required)

Step 5: PaymentService.processPayment() — IDEMPOTENCY CHECK (CRITICAL!)
  - idempotencyRepository.findByKey(idempotencyKey)
  - SQL: SELECT * FROM idempotency_records WHERE key = 'order-789-payment-001'
  
  Case A: Record exists and status = COMPLETED
    └─► Return stored payment (NO new charge, NO new wallet movement)
    └─► This is the SAFEGUARD against double-payment
    
  Case B: Record exists and status = IN_PROGRESS
    └─► Another request is processing this same key
    └─► Wait up to 30 seconds, then re-check
    └─► If still IN_PROGRESS → 409 Conflict (or return stored if completed)
    
  Case C: Record does not exist
    └─► Continue to Step 6

Step 6: Create idempotency record (IN_PROGRESS)
  - IdempotencyRecord record = new IdempotencyRecord()
  - record.setKey(idempotencyKey)
  - record.setPayload(requestJson)
  - record.setStatus("IN_PROGRESS")
  - record.setExpiresAt(now + 24 hours)
  - idempotencyRepository.save(record)
  - SQL: INSERT INTO idempotency_records (key, payload, status, expires_at) VALUES (?, ?, 'IN_PROGRESS', ?)
  - This is committed immediately (own transaction) so concurrent requests see it

Step 7: Create Payment record (PENDING)
  - Payment payment = new Payment()
  - payment.setOrderId(789)
  - payment.setCustomerId(42)  // from JWT, NOT body!
  - payment.setAmount(299.00)
  - payment.setMethod("CASH_ON_DELIVERY")
  - payment.setStatus("PENDING")
  - paymentRepository.save(payment)
  - SQL: INSERT INTO payments (order_id, customer_id, amount, method, status) VALUES (789, 42, 299.00, 'CASH_ON_DELIVERY', 'PENDING')
  - Returns: paymentId = 123

Step 8: Charge payment (OUTSIDE transaction!)
  - WHY OUTSIDE? PSP calls are slow (2-5 seconds). We don't want to hold a DB transaction open.
  - paymentGateway.charge(orderId, amount, method)
  - For CASH_ON_DELIVERY: no actual PSP call, just mark as SETTLED
  - For ONLINE: call Razorpay/Stripe API
  - If PSP call fails → throw PaymentGatewayException

Step 9: Settle payment + outbox (INSIDE transaction)
  - @Transactional
    a) payment.setStatus("SETTLED")
    b) payment.setProviderRef("pay_ABC123")
    c) outboxClient.save("Payment", 123, "payment_settled", payload)
       - SQL: INSERT INTO outbox_events (...) VALUES ('Payment', 123, 'payment_settled', {...}, 'PENDING')
    d) COMMIT
  - If crash here → outbox event not published, but payment is SETTLED
  - Reconciliation sweep will fix drift

Step 10: Wallet movement (if WALLET method)
  - If paymentMethod = "WALLET":
    @Transactional
      a) walletService.debit(customerId, amount)
         - SQL: UPDATE wallet_balances SET balance = balance - 299.00 WHERE customer_id = 42
         - INSERT INTO wallet_transactions (wallet_balance_id, type, amount, reference)
      b) COMMIT

Step 11: Update idempotency record (COMPLETED)
  - idempotencyRecord.setStatus("COMPLETED")
  - idempotencyRecord.setCompletedAt(now)
  - Save (no new transaction needed if still in Step 9's tx)

Step 12: Return response
  - paymentMapper.toPaymentResponse(payment)
  - HTTP 200 OK
  - Body: {paymentId: 123, orderId: 789, status: "SETTLED", ...}
```

#### 6.1.5 Database tables touched

| Table | Operation | Purpose |
|-------|-----------|---------|
| `idempotency_records` | INSERT, UPDATE | Prevent double-charging |
| `payments` | INSERT, UPDATE | Payment record |
| `wallet_balances` | UPDATE (if WALLET) | Debit customer wallet |
| `wallet_transactions` | INSERT (if WALLET) | Audit trail |
| `outbox_events` | INSERT | Publish payment_settled event |

#### 6.1.6 Idempotency deep dive

The idempotency pattern prevents double-charging:

```
Request 1 (first attempt):
  1. Check idempotency key → NOT FOUND
  2. Create idempotency record (IN_PROGRESS)
  3. Create payment (PENDING)
  4. Charge PSP
  5. Update payment (SETTLED)
  6. Update idempotency record (COMPLETED)
  7. Return payment

Request 2 (duplicate, same Idempotency-Key):
  1. Check idempotency key → FOUND (status=COMPLETED)
  2. Return stored payment (steps 3-7 skipped)

Request 3 (concurrent, same key, arrives during step 4):
  1. Check idempotency key → FOUND (status=IN_PROGRESS)
  2. Wait up to 30 seconds
  3. Re-check → if COMPLETED, return stored payment
  4. If still IN_PROGRESS → 409 Conflict
```

#### 6.1.7 Security

- **CustomerId from JWT**: Even if the request body contains a different customerId, the JWT subject is used. This prevents unauthorized payments.
- **Idempotency key**: Required header. Prevents replay attacks.

#### 6.1.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 400 Bad Request | Validation error | Missing orderId, negative amount |
| 409 Conflict | Concurrent duplicate request | Same Idempotency-Key already IN_PROGRESS |
| 502 Bad Gateway | PSP (payment gateway) unavailable | Razorpay down |
| 500 Internal Server Error | Database error | Connection pool exhausted |

---



## 7. Delivery Service

**Port**: 8094  
**Database**: `delivery_db`  
**Pattern**: Agent self-service + service-to-service assignment

### 7.1 PUT /api/v1/delivery/toggle-availability

**Purpose**: Delivery agent toggles their availability to accept new orders.

#### 7.1.1 Request format
```
PUT /api/v1/delivery/toggle-availability?available=true
Headers: Authorization: Bearer <agent_jwt>
```

#### 7.1.2 Response format
```json
{
  "id": 10,
  "userId": 42,
  "name": "Rider Raj",
  "phone": "9199999999",
  "vehicleType": "BIKE",
  "vehicleNumber": "KA01AB1234",
  "isActive": true,
  "createdAt": "2026-09-16T10:00:00Z",
  "updatedAt": "2026-09-16T13:00:00Z"
}
```

#### 7.1.3 Controller code (real)
```java
@PutMapping("/api/v1/delivery/toggle-availability")
@Transactional
public DeliveryAgent toggleAvailability(@AuthenticationPrincipal TokenPrincipal principal,
                                        @RequestParam(required = false) Boolean available) {
    // 1. Get or create agent profile (lazy creation)
    DeliveryAgent agent = agentRepository.findById(currentAgent(principal).getId())
            .orElseThrow();
    
    // 2. Toggle availability
    //    If available param is null, default to TRUE (agent wants to go online)
    agent.setIsActive(available == null || available);
    
    // 3. Save
    return agentRepository.save(agent);
}
```

#### 7.1.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - PUT /api/v1/delivery/toggle-availability?available=true
  - Headers: Authorization: Bearer <agent_jwt>

Step 2: JWT validation
  - TokenPrincipal: userId=42, role="DELIVERY_AGENT", scope="DELIVERY_AGENT"

Step 3: Controller method
  - RiderSelfController.toggleAvailability(principal, available=true)

Step 4: currentAgent(principal) — LAZY AGENT CREATION
  - This is a critical pattern in the delivery service
  - AgentProvisioner.getOrCreate(principal.userId())
  - SQL: SELECT * FROM delivery_agents WHERE user_id = 42
  
  Case A: Agent exists
    └─► Return existing DeliveryAgent
  
  Case B: Agent does NOT exist
    └─► Create new DeliveryAgent:
        - user_id = 42
        - name = "" (empty, agent fills later via updateProfile)
        - phone = ""
        - vehicleType = null
        - vehicleNumber = null
        - isActive = true
    └─► INSERT INTO delivery_agents (user_id, name, phone, is_active) VALUES (42, '', '', true)
    └─► Return new agent

  WHY lazy creation? 
  - When an agent registers (via identity service), we don't create a delivery_agents row.
  - Instead, we create it on first authenticated call. This simplifies registration
  and allows agents to exist without a delivery profile until needed.

Step 5: Update agent
  - agent.setIsActive(true)
  - This is a simple boolean toggle

Step 6: Save to database
  - agentRepository.save(agent)
  - SQL: UPDATE delivery_agents SET is_active = true, updated_at = NOW() WHERE id = ?

Step 7: Return response
  - HTTP 200 OK
  - Body: {id: 10, userId: 42, name: "", ..., isActive: true}
```

#### 7.1.5 Database tables touched

| Table | Operation | When |
|-------|-----------|------|
| `delivery_agents` | SELECT (lazy check) | Step 4 |
| `delivery_agents` | INSERT (lazy creation) | Step 4 (if new) |
| `delivery_agents` | UPDATE | Step 6 |

#### 7.1.6 Security

- **Agent-only**: The endpoint doesn't explicitly check role, but only agents have JWT with `DELIVERY_AGENT` scope.
- **IDOR protection**: `currentAgent(principal)` ensures the agent can only modify their own profile.

### 7.2 GET /api/v1/delivery/earnings/summary

**Purpose**: Aggregated earnings for the rider dashboard — today's, this week's, and lifetime totals, plus delivery count. Earnings are recorded and stored in the **payment service** (COD wallet ledger); the delivery service only aggregates them via a mesh call.

#### 7.2.1 Request format
```
GET /api/v1/delivery/earnings/summary
Headers: Authorization: Bearer <agent_jwt>
```

#### 7.2.2 Response format
```json
{
  "agentId": 10,
  "today": 245.50,
  "thisWeek": 1320.00,
  "total": 18450.75,
  "deliveries": 87
}
```

#### 7.2.3 Controller code (real — `RiderSelfController`)
```java
@GetMapping("/api/v1/delivery/earnings/summary")
@Transactional(readOnly = true)
public Map<String, Object> earningsSummary(@AuthenticationPrincipal TokenPrincipal principal) {
    Long agentId = currentAgent(principal).getId();
    List<Map<String, Object>> earnings = riderOpsService.getEarnings(agentId);
    double today = earnings.stream()
            .filter(e -> "TODAY".equalsIgnoreCase(String.valueOf(e.get("period"))))
            .mapToDouble(e -> ((Number) e.getOrDefault("amount", 0)).doubleValue())
            .sum();
    double week = earnings.stream()
            .filter(e -> "WEEK".equalsIgnoreCase(String.valueOf(e.get("period"))))
            .mapToDouble(e -> ((Number) e.getOrDefault("amount", 0)).doubleValue())
            .sum();
    double total = earnings.stream()
            .filter(e -> "TOTAL".equalsIgnoreCase(String.valueOf(e.get("period"))))
            .mapToDouble(e -> ((Number) e.getOrDefault("amount", 0)).doubleValue())
            .sum();
    return Map.of(
            "agentId", agentId,
            "today", today,
            "thisWeek", week,
            "total", total,
            "deliveries", earnings.stream()
                    .mapToLong(e -> ((Number) e.getOrDefault("deliveries", 0)).longValue())
                    .sum());
}
```

#### 7.2.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/delivery/earnings/summary
  - Headers: Authorization: Bearer <agent_jwt>

Step 2: Gateway routing
  - Path "/api/v1/delivery/**" → "delivery" route → http://delivery-service:8094

Step 3: JWT validation
  - TokenPrincipal extracted: agentId (userId), scope="DELIVERY_AGENT"

Step 4: Resolve agent (lazy provision)
  - currentAgent(principal) → agentRepository.findById(agentId)
  - If agent row doesn't exist yet → EntityNotFoundException → 500
    (the agent should have been provisioned on first login/availability toggle)

Step 5: Mesh call to payment service
  - RiderOpsService.getEarnings(agentId):
      paymentClient.getEarnings(agentId)
  - PaymentServiceClient.getEarnings(agentId):
      GET {paymentServiceUrl}/api/v1/internal/delivery/earnings/{agentId}
      Headers: Authorization: Bearer <service_jwt> (X-Service-Token)
  - Payment service:
      a) Validates service token (ServiceJwtAuthFilter)
      b) Queries earnings records for this agent (payment ledger)
      c) Returns List<Map> with fields: period (TODAY/WEEK/TOTAL),
         amount, deliveries
  - On failure (RestClientException):
      log.error(...) → RuntimeException("Payment service unavailable") → 503

Step 6: Null-safety (defensive)
  - riderOpsService.getEarnings: `return result != null ? result : List.of();`
  - This was a previous NPE source (fixed) — the client now tolerates a null body.

Step 7: Aggregate by period (in-JVM)
  - TODAY: sum of amount where period = "TODAY"
  - WEEK: sum of amount where period = "WEEK"
  - TOTAL: sum of amount where period = "TOTAL"
  - deliveries: count of all records' deliveries field

Step 8: Response
  - Map {agentId, today, thisWeek, total, deliveries}
  - Jackson serializes → JSON
  - HTTP 200 OK
```

#### 7.2.5 Database tables touched

| Service | Table | Operation |
|---------|-------|-----------|
| Delivery | `delivery_agents` | SELECT (agent lookup) |
| Payment | `earnings` / `wallet_transactions` | SELECT (aggregation) |

The earnings records are written by the payment service when the rider completes a COD order (via `PaymentServiceClient.recordEarning`).

#### 7.2.6 Caching behavior

- **Not cached** (earnings change frequently; freshness matters for the rider).

#### 7.2.7 Security

- **Agent-only**: Only a DELIVERY_AGENT JWT reaches this endpoint.
- **Self-only**: The agentId is derived from the JWT subject, not the URL — no IDOR risk.
- **Mesh auth**: The call to payment service uses a service JWT (X-Service-Token), not the agent's personal JWT.

#### 7.2.8 Common failure modes

| HTTP Status | Cause | Example |
|-------------|-------|---------|
| 503 | Payment service unreachable | Payment pod down |
| 500 | Agent row missing (lazy provision not done) | New agent never logged in |
| 401 | Missing/invalid JWT | No Authorization header |

---



## 8. Search Service

**Port**: 8082  
**Database**: PostgreSQL (projection tables) + optional Elasticsearch  
**Pattern**: Read-optimized search with multi-tier caching

### 8.1 GET /api/v1/search

**Purpose**: Unified search across restaurants and menu items.

#### 8.1.1 Request format
```
GET /api/v1/search?q=pizza&lat=12.97&lng=77.59&radiusKm=5
```

#### 8.1.2 Response format
```json
{
  "query": "pizza",
  "results": [
    {
      "type": "restaurant",
      "id": 55,
      "name": "Pizza Hub",
      "cuisine": "Italian",
      "rating": 4.4,
      "distanceKm": 1.2
    },
    {
      "type": "menu_item",
      "id": 1024,
      "name": "Margherita Pizza",
      "restaurantName": "Pizza Hub",
      "price": 299.00,
      "distanceKm": 1.2,
      "available": true
    }
  ],
  "facets": {
    "cuisines": [
      {"name": "Italian", "count": 42},
      {"name": "American", "count": 15}
    ]
  }
}
```

#### 8.1.3 Controller code (real)
```java
@GetMapping
public ResponseEntity<UnifiedSearchResponse> unifiedSearch(@RequestParam String keyword) {
    // 1. Delegate to service (caching, ES/Postgres logic hidden)
    UnifiedSearchResponse response = searchService.unifiedSearch(keyword);
    
    // 2. Return 200 OK
    return ResponseEntity.ok(response);
}
```

#### 8.1.4 Detailed E2E flow (numbered steps)

```
Step 1: HTTP Request
  - GET /api/v1/search?q=pizza&lat=12.97&lng=77.59&radiusKm=5
  - No authentication required

Step 2: Gateway routing
  - Path: /api/v1/search/**
  - Matches "search" route → forwards to http://search-service:8082

Step 3: Controller
  - SearchController.unifiedSearch("pizza")
  - No validation (keyword is raw string)

Step 4: SearchService.unifiedSearch() — CACHE CHECK
  - Build cache key: "search:l1:pizza:12.97:77.59:5.0"
  - Check L1 cache (Caffeine, in-JVM):
    - If hit → return cached response (~0.5ms)
  - Check L2 cache (Redis):
    - If hit → populate L1, return cached response (~2ms)
  
  If cache miss → continue to Step 5

Step 5: Elasticsearch query (primary path)
  - IF Elasticsearch is configured and healthy:
    a) Build query DSL:
       {
         "bool": {
           "must": [
             {
               "multi_match": {
                 "fields": ["name^3", "cuisineSummary^2", "description"],
                 "query": "pizza",
                 "fuzziness": "AUTO",
                 "prefixLength": 2
               }
             }
           ],
           "filter": [
             {"term": {"isActive": true}},
             {
               "geo_distance": {
                 "field": "location",
                 "location": {"lat": 12.97, "lon": 77.59},
                 "distance": "5km"
               }
             }
           ]
         }
       }
    b) Execute: elasticsearchClient.search(s -> s.index("restaurants").query(...))
    c) Map hits to RestaurantDocument
    d) ALSO search menu_items index
    e) Merge and sort by relevance + distance
  
  Step 6: PostgreSQL fallback (if ES down or not configured)
    a) JdbcTemplate.query("""
        SELECT r.id, r.name, r.cuisine_summary, 
               ST_Distance(r.location, ST_MakePoint(?, ?)) as distance
        FROM restaurant_search r
        WHERE r.name ILIKE ? OR r.cuisine_summary ILIKE ?
        ORDER BY distance ASC
        LIMIT 50
      """, ...)
    b) Uses pg_trgm extension for fuzzy matching

Step 7: Cache population
  - L1 cache: put(cacheKey, response, 1 minute)
  - L2 cache: redis.set("search:l2:" + cacheKey, json, 5 minutes)

Step 8: Return response
  - HTTP 200 OK
  - Body: UnifiedSearchResponse JSON
```

#### 8.1.5 Database tables (fallback path)

| Table | Query | Purpose |
|-------|-------|---------|
| `restaurant_search` | `SELECT ... WHERE name ILIKE ?` | Restaurant search projection |
| `menu_item_search` | `SELECT ... WHERE name ILIKE ?` | Menu item search projection |

**Projection tables** are denormalized, updated via outbox events from restaurant service.

#### 8.1.6 Elasticsearch index mapping (if enabled)

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1,
    "refresh_interval": "5s",
    "analysis": {
      "analyzer": {
        "autocomplete_analyzer": {
          "tokenizer": "autocomplete_tokenizer",
          "filter": ["lowercase", "autocomplete_filter"]
        },
        "phonetic_analyzer": {
          "tokenizer": "standard",
          "filter": ["lowercase", "phonetic_filter"]
        }
      },
      "tokenizer": {
        "autocomplete_tokenizer": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20
        }
      },
      "filter": {
        "autocomplete_filter": {
          "type": "edge_ngram",
          "min_gram": 2,
          "max_gram": 20
        },
        "phonetic_filter": {
          "type": "phonetic",
          "encoder": "metaphone"
        }
      }
    }
  },
  "mappings": {
    "properties": {
      "id": {"type": "long"},
      "name": {
        "type": "text",
        "analyzer": "autocomplete_analyzer",
        "search_analyzer": "standard",
        "fields": {
          "keyword": {"type": "keyword"},
          "phonetic": {"type": "text", "analyzer": "phonetic_analyzer"}
        }
      },
      "cuisineSummary": {
        "type": "text",
        "analyzer": "autocomplete_analyzer"
      },
      "location": {"type": "geo_point"},
      "averageRating": {"type": "float"},
      "isActive": {"type": "boolean"}
    }
  }
}
```

#### 8.1.7 Caching strategy (3-tier)

| Tier | Technology | TTL | Size limit | Purpose |
|------|-----------|-----|------------|---------|
| L1 | Caffeine (in-JVM) | 1 minute | 5,000 entries | Ultra-fast local cache |
| L2 | Redis Cluster | 5 minutes | ~100K keys | Shared cache across instances |
| L3 | PostgreSQL | N/A | Full dataset | Source of truth |

**Cache invalidation**: When restaurant/menu data changes, the outbox event triggers cache eviction via `CacheInvalidationSubscriber`.

---



## 9. Common Patterns (Deep Dive)

### 9.1 Authentication & Authorization

Every protected endpoint follows this pattern:

```java
@GetMapping("/something")
public Response get(@AuthenticationPrincipal TokenPrincipal principal, ...) {
    // 1. PrincipalGuard checks
    PrincipalGuard.requireAuthenticated(principal);
    // OR
    PrincipalGuard.requireSelfOrAdmin(principal, resourceOwnerId);
    // OR
    requireOwnerOrAdmin(principal, restaurantId);
    
    // 2. Business logic
    // ...
}
```

**PrincipalGuard implementation** (real code):
```java
public final class PrincipalGuard {
    private PrincipalGuard() {}  // utility class
    
    public static void requireAuthenticated(TokenPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("Authentication required");
        }
    }
    
    public static void requireSelfOrAdmin(TokenPrincipal principal, Long resourceOwnerId) {
        requireAuthenticated(principal);
        boolean isAdmin = "ADMIN".equalsIgnoreCase(String.valueOf(principal.scope()));
        if (!isAdmin && !resourceOwnerId.equals(principal.userId())) {
            throw new AccessDeniedException("Cannot act on another user's resource");
        }
    }
    
    public static void requireOwnerOrAdmin(TokenPrincipal principal, Long restaurantId) {
        requireAuthenticated(principal);
        if ("ADMIN".equalsIgnoreCase(String.valueOf(principal.scope()))) {
            return;  // admins can do anything
        }
        // Load restaurant and check ownership
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
        if (!restaurant.getOwnerId().equals(principal.userId())) {
            throw new AccessDeniedException("Not the restaurant owner");
        }
    }
}
```

**Why this matters**: 
- `requireSelfOrAdmin` prevents IDOR (Insecure Direct Object Reference) attacks.
- `requireOwnerOrAdmin` ensures only the restaurant owner or admin can modify menu items.

### 9.2 Error Handling

**Global exception handler** (in `platform-lib`):
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return Map.of("error", "VALIDATION_ERROR", "message", message);
    }
    
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBusiness(BusinessException e) {
        return Map.of("error", "BUSINESS_ERROR", "message", e.getMessage());
    }
    
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, String> handleNotFound(ResourceNotFoundException e) {
        return Map.of("error", "NOT_FOUND", "message", e.getMessage());
    }
    
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> handleAccessDenied() {
        return Map.of("error", "ACCESS_DENIED", "message", "Insufficient permissions");
    }
    
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, String> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return Map.of("error", "INTERNAL_ERROR", "message", "An unexpected error occurred");
    }
}
```

**Error response format** (consistent across all services):
```json
{
  "error": "VALIDATION_ERROR",
  "message": "email: must not be blank, password: size must be between 8 and 128",
  "timestamp": "2026-09-16T13:00:00Z"
}
```

### 9.3 Caching Strategy

**Cache-aside pattern with single-flight coalescing**:

```java
public class RedisCacheService {
    // L1: Caffeine (in-JVM, per-instance)
    private final Caffeine<Object, Object> l1Cache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build();
    
    // L2: Redis (shared across instances)
    private final RedisTemplate<String, String> redisTemplate;
    
    public <T> T get(String key, Class<T> type) {
        // 1. Check L1
        T l1 = (T) l1Cache.getIfPresent(key);
        if (l1 != null) return l1;
        
        // 2. Check L2
        String json = redisTemplate.opsForValue().get(key);
        if (json != null) {
            T value = objectMapper.readValue(json, type);
            l1Cache.put(key, value);  // populate L1
            return value;
        }
        
        // 3. Cache miss → caller fetches from DB
        return null;
    }
    
    public void put(String key, Object value, long ttlSeconds) {
        String json = objectMapper.writeValueAsString(value);
        // L2
        redisTemplate.opsForValue().set(key, json, ttlSeconds, TimeUnit.SECONDS);
        // L1 (shorter TTL to avoid stale data)
        l1Cache.put(key, value);
    }
}
```

**Single-flight coalescing** (prevents cache stampede):
```java
public <T> T getOrLoad(String key, Supplier<T> loader, long ttlSeconds) {
    // 1. Check L1
    T cached = (T) l1Cache.getIfPresent(key);
    if (cached != null) return cached;
    
    // 2. Single-flight: coalesce concurrent requests for same key
    CompletableFuture<T> future = inFlightLoads.computeIfAbsent(key, k -> {
        return CompletableFuture.supplyAsync(() -> {
            try {
                T value = loader.get();  // DB call
                put(key, value, ttlSeconds);
                return value;
            } finally {
                inFlightLoads.remove(k);  // cleanup
            }
        });
    });
    
    try {
        return future.get(5, TimeUnit.SECONDS);  // wait with timeout
    } catch (TimeoutException e) {
        // Return stale data if available, otherwise fall back to DB
        return (T) l1Cache.getIfPresent(key);
    }
}
```

### 9.4 Outbox Pattern

**Why outbox?** In a microservices architecture, we need to publish events to Kafka when data changes. But if we publish to Kafka inside the service method, and the service crashes after DB commit but before Kafka publish, the event is lost.

**Solution**: Write the event to an `outbox_events` table in the SAME transaction as the business data. A background poller (`OutboxPollPublisher`) picks up PENDING events and publishes them to Kafka.

```java
// Inside a @Transactional service method
@Transactional
public Order createOrder(...) {
    // 1. Save business data
    Order order = orderRepository.save(order);
    OrderItem item = orderItemRepository.save(item);
    
    // 2. Save outbox event (SAME transaction!)
    outboxClient.save("Order", order.getId(), "OrderCreated", Map.of(
        "orderId", order.getId(),
        "customerId", order.getCustomerId(),
        "restaurantId", order.getRestaurantId(),
        "total", order.getTotal()
    ));
    
    // 3. Both inserts commit atomically
    // If crash here → both rolled back (no orphan order, no missing event)
    
    return order;
}

// Background poller (separate thread)
@Component
public class OutboxPollPublisher {
    @Scheduled(fixedRate = 1000)  // every 1 second
    public void publishPending() {
        // 1. Claim batch of PENDING events
        List<OutboxEvent> events = outboxEventRepository.findTop100ByStatusOrderById("PENDING");
        
        for (OutboxEvent event : events) {
            try {
                // 2. Publish to Kafka
                kafkaTemplate.send(event.getAggregateType(), event.getEventType(), event.getPayload());
                
                // 3. Mark as PUBLISHED
                event.setStatus("PUBLISHED");
                event.setPublishedAt(Instant.now());
                outboxEventRepository.save(event);
            } catch (Exception e) {
                // 4. Mark as FAILED (will retry next poll)
                event.setStatus("FAILED");
                event.setErrorMessage(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
```

**Event consumers** (e.g., search service):
```java
@KafkaListener(topics = "menu-events", groupId = "search-service")
public void onMenuItemChanged(MenuItemChangedEvent event) {
    // 1. Update search projection
    MenuItemDocument doc = new MenuItemDocument();
    doc.setId(event.menuItemId());
    doc.setName(event.name());
    // ...
    
    // 2. Index in Elasticsearch (or update PostgreSQL projection)
    elasticsearchClient.index(i -> i
        .index("menu_items")
        .id(String.valueOf(event.menuItemId()))
        .document(doc)
    );
}
```

---



## 10. Database Schema Reference

### 10.1 Identity Service
```sql
-- Users (shared across all roles)
CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,  -- CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT, ADMIN
    active BOOLEAN DEFAULT TRUE,
    email_verified BOOLEAN DEFAULT FALSE,
    phone_verified BOOLEAN DEFAULT FALSE,
    profile_completed BOOLEAN DEFAULT FALSE,
    totp_enabled BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Customers (extends User)
CREATE TABLE customers (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    profile_image_url TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Addresses (for customers)
CREATE TABLE addresses (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    label VARCHAR(50),
    address_line1 VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    pincode VARCHAR(10) NOT NULL,
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    is_default BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Refresh tokens
CREATE TABLE refresh_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Outbox events
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,  -- 'User', 'Order', 'Restaurant'
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,      -- 'UserRegistered', 'OrderCreated'
    payload JSONB NOT NULL,
    headers JSONB DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    status VARCHAR(20) DEFAULT 'PENDING'   -- PENDING, PUBLISHED, FAILED
);
```

### 10.2 Restaurant Service
```sql
-- Restaurants
CREATE TABLE restaurants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    cuisine_id BIGINT REFERENCES cuisines(id),
    owner_id BIGINT NOT NULL,  -- references users.id
    address JSONB,
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    geog GEOGRAPHY(Point, 4326),  -- PostGIS geography column
    is_active BOOLEAN DEFAULT TRUE,
    is_open BOOLEAN DEFAULT TRUE,
    opening_time TIME,
    closing_time TIME,
    delivery_fee DECIMAL(10, 2) DEFAULT 0,
    minimum_order_amount DECIMAL(10, 2) DEFAULT 0,
    average_delivery_time INT DEFAULT 30,
    free_delivery_available BOOLEAN DEFAULT FALSE,
    free_delivery_above DECIMAL(10, 2),
    is_pure_veg BOOLEAN DEFAULT FALSE,
    phone VARCHAR(20),
    fssai_number VARCHAR(50),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- PostGIS index for spatial queries
CREATE INDEX idx_restaurants_geog ON restaurants USING GIST (geog);
CREATE INDEX idx_restaurants_is_active ON restaurants(is_active);

-- Menu categories
CREATE TABLE menu_categories (
    id BIGSERIAL PRIMARY KEY,
    restaurant_id BIGINT REFERENCES restaurants(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    display_order INT DEFAULT 0,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Menu items
CREATE TABLE menu_items (
    id BIGSERIAL PRIMARY KEY,
    restaurant_id BIGINT REFERENCES restaurants(id) ON DELETE CASCADE,
    category_id BIGINT REFERENCES menu_categories(id),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    is_available BOOLEAN DEFAULT TRUE,
    is_veg BOOLEAN DEFAULT TRUE,
    is_spicy BOOLEAN DEFAULT FALSE,
    spice_level VARCHAR(20),  -- MILD, MEDIUM, HOT
    preparation_time INT,  -- minutes
    stock_quantity INT DEFAULT 0,
    image_url TEXT,
    tags TEXT[],
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Search projection (updated via outbox)
CREATE TABLE restaurant_search (
    id BIGINT PRIMARY KEY,
    name TEXT,
    cuisine_summary TEXT,
    description TEXT,
    latitude DECIMAL(10, 7),
    longitude DECIMAL(10, 7),
    average_rating DECIMAL(3, 2),
    delivery_radius_km INT,
    is_active BOOLEAN,
    -- Full-text search vector
    search_vector TSVECTOR
);
CREATE INDEX idx_restaurant_search_vector ON restaurant_search USING GIN(search_vector);
```

### 10.3 Order Service
```sql
-- Orders
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,  -- PENDING, CONFIRMED, PREPARING, OUT_FOR_DELIVERY, DELIVERED, CANCELLED
    total DECIMAL(10, 2) NOT NULL,
    payment_method VARCHAR(50),
    payment_status VARCHAR(50) DEFAULT 'PENDING',
    delivery_address_id BIGINT,
    delivery_agent_id BIGINT,
    special_instructions TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Order items
CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT REFERENCES orders(id) ON DELETE CASCADE,
    menu_item_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    quantity INT NOT NULL,
    modifiers JSONB,  -- [{modifierId, optionId, quantity}]
    special_instructions TEXT,
    subtotal DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Saga instances
CREATE TABLE saga_instances (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    saga_type VARCHAR(100) NOT NULL,  -- 'ORDER_CREATION'
    status VARCHAR(50) NOT NULL,  -- STARTED, COMPLETED, FAILED, COMPENSATING
    current_step VARCHAR(100),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Saga steps
CREATE TABLE saga_steps (
    id BIGSERIAL PRIMARY KEY,
    saga_instance_id BIGINT REFERENCES saga_instances(id),
    step_name VARCHAR(100) NOT NULL,  -- 'RESERVE_STOCK', 'CHARGE_PAYMENT'
    status VARCHAR(50) NOT NULL,  -- PENDING, COMPLETED, FAILED
    payload JSONB,
    error_message TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ
);

-- Cart items
CREATE TABLE cart_items (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    quantity INT NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(customer_id, menu_item_id)
);
```

### 10.4 Payment Service
```sql
-- Payments
CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    customer_id BIGINT NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    method VARCHAR(50) NOT NULL,  -- WALLET, CASH_ON_DELIVERY, RAZORPAY, etc.
    status VARCHAR(50) NOT NULL,  -- PENDING, PROCESSING, SETTLED, REFUNDED, FAILED
    provider_ref VARCHAR(255),  -- PSP transaction ID
    metadata JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Wallet balances
CREATE TABLE wallet_balances (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT UNIQUE NOT NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    version BIGINT DEFAULT 0,  -- optimistic locking
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Wallet transactions
CREATE TABLE wallet_transactions (
    id BIGSERIAL PRIMARY KEY,
    wallet_balance_id BIGINT REFERENCES wallet_balances(id),
    type VARCHAR(50) NOT NULL,  -- CREDIT, DEBIT, REFUND
    amount DECIMAL(10, 2) NOT NULL,
    reference VARCHAR(255),  -- e.g., "CREDIT:order-789"
    balance_after DECIMAL(12, 2) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Idempotency records
CREATE TABLE idempotency_records (
    key VARCHAR(255) PRIMARY KEY,
    payload JSONB NOT NULL,
    status VARCHAR(50) NOT NULL,  -- IN_PROGRESS, COMPLETED, FAILED
    result JSONB,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    completed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_idempotency_expires ON idempotency_records(expires_at);
```

### 10.5 Delivery Service
```sql
-- Delivery agents
CREATE TABLE delivery_agents (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,  -- references users.id
    name VARCHAR(255),
    phone VARCHAR(20),
    vehicle_type VARCHAR(50),  -- BIKE, SCOOTER, CAR
    vehicle_number VARCHAR(20),
    is_active BOOLEAN DEFAULT TRUE,
    current_latitude DECIMAL(10, 7),
    current_longitude DECIMAL(10, 7),
    last_location_update TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Delivery assignments
CREATE TABLE delivery_assignments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    agent_id BIGINT REFERENCES delivery_agents(id),
    status VARCHAR(50) NOT NULL,  -- ASSIGNED, ACCEPTED, PICKED_UP, DELIVERED
    assigned_at TIMESTAMPTZ DEFAULT NOW(),
    accepted_at TIMESTAMPTZ,
    picked_up_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ
);

-- Rider location updates (time-series)
CREATE TABLE rider_location_updates (
    id BIGSERIAL PRIMARY KEY,
    agent_id BIGINT REFERENCES delivery_agents(id),
    latitude DECIMAL(10, 7) NOT NULL,
    longitude DECIMAL(10, 7) NOT NULL,
    recorded_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_rider_location_agent_recorded ON rider_location_updates(agent_id, recorded_at DESC);
```

---



## 11. Security Architecture (Detailed)

### 11.1 JWT Token Structure

**Access tokens** (issued by `JwtService.issue`):

```
Header:
{
  "alg": "RS256",
  "typ": "access",
  "kid": "key-id-1"
}

Payload:
{
  "sub": "42",              // Subject: user ID (always numeric string)
  "email": "alice@example.com",
  "role": "CUSTOMER",       // Role: CUSTOMER, RESTAURANT_OWNER, DELIVERY_AGENT, ADMIN
  "scope": "CUSTOMER",      // Scope: used for fine-grained access control
  "iat": 1726461600,        // Issued at: Unix timestamp (seconds)
  "exp": 1726462500,        // Expires at: iat + 900 (15 minutes)
  "iss": "bhukkad",         // Issuer: validates token origin
  "aud": ["bhukkad-clients"], // Audience
  "jti": "a1b2c3d4-..."     // Unique token id (UUID)
}

Signature:
  RSASSA-PKCS1-v1_5(
    SHA256(base64(Header) + "." + base64(Payload)),
    RSA private key (kid matches JWKS)
  )
```

**Verification**: Each service validates the RS256 signature using the RSA public key from `/.well-known/jwks.json` (cached for 60s). During the legacy cutover window, `JwtService.introspect` also accepts HS256 tokens signed with the shared secret, so pre-cutover clients keep working.

**Secret key requirements** (RS256):
- RSA 2048-bit key pair (rotatable via `KeyRotationController`)
- Private key: `app.jwt.signing-key` (env var or K8s secret)
- Public key: published at `/.well-known/jwks.json`
- Never committed to git

**Refresh tokens** (opaque, server-side):
- 64 random bytes, Base64url-encoded (opaque to client)
- Stored **SHA-256 hashed** in `refresh_tokens` table
- 7-day expiry, per-(user, device) rotation
- Reuse of a rotated token revokes the whole family

**TTL**: Access token = 15 minutes (`app.jwt.access-ttl-minutes`). Long-lived sessions are renewed via rotating refresh tokens at `/api/v1/auth/refresh`, not via fat access tokens.

### 11.2 Service-to-Service Authentication

Internal endpoints (`/api/v1/internal/**`) require a **service JWT**:

```java
// In OrderService calling RestaurantClient
@Service
public class RestaurantClient {
    private final ServiceJwtAuthTokenProvider tokenProvider;
    
    public List<StockReservationLine> reserveStock(List<StockReservationLine> items) {
        String serviceToken = tokenProvider.getToken();
        // serviceToken is a JWT with:
        // - sub: "order-service"
        // - role: "SERVICE"
        // - scope: "SERVICE"
        
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(serviceToken);
        headers.set("X-Service-Name", "order-service");
        headers.set("X-Request-Id", UUID.randomUUID().toString());
        
        return restTemplate.exchange(
            "http://restaurant:8091/api/v1/internal/stock/reserve",
            HttpMethod.POST,
            new HttpEntity<>(items, headers),
            new ParameterizedTypeReference<List<StockReservationLine>>() {}
        ).getBody();
    }
}
```

**Verified by**: `ServiceJwtAuthFilter` in `platform-lib`:
```java
@Component
public class ServiceJwtAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;  // not a service request, let other filters handle
        }
        
        String token = auth.substring(7);
        JwtParser parser = Jwts.parserBuilder()
            .setSigningKey(serviceJwtSecret)
            .build();
        
        Claims claims = parser.parseClaimsJws(token).getBody();
        String role = claims.get("role", String.class);
        
        if (!"SERVICE".equals(role)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid service token");
            return;
        }
        
        // Set authentication in context
        Authentication auth = new ServiceAuthenticationToken(claims);
        SecurityContextHolder.getContext().setAuthentication(auth);
        
        filterChain.doFilter(request, response);
    }
}
```

---



## 12. Observability

### 12.1 Metrics (Micrometer + Prometheus)

Every service exposes `/actuator/prometheus` with these key metrics:

| Metric | Type | Meaning |
|--------|------|---------|
| `http.server.requests` | Counter + Timer | Request count, latency by endpoint/method/status |
| `order.requests` | Counter | Order creation rate |
| `payment.charge.duration` | Timer | Payment charge latency (p50, p95, p99) |
| `cache.hit.ratio` | Gauge | Redis cache hit ratio (0.0-1.0) |
| `circuitbreaker.calls` | Counter | Circuit breaker state changes |
| `outbox.published` | Counter | Events successfully published to Kafka |
| `outbox.failed` | Counter | Events that failed to publish |

Example Prometheus query:
```promql
# P95 latency for order creation
histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{uri="/api/v1/orders", method="POST"}[5m])) by (le)
```

### 12.2 Tracing (Zipkin / Tempo)

Distributed trace propagation:
```java
// HTTP
X-B3-TraceId: 80f198ee56343ba864fe8b2a57d3bbc7
X-B3-SpanId: 05d3c9c3a7f2a9d1
X-B3-Sampled: 1

// Kafka message header
X-B3-TraceId: 80f198ee56343ba864fe8b2a57d3bbc7
X-B3-SpanId: 05d3c9c3a7f2a9d1
```

Trace flow for order creation:
```
Client → Gateway → Order Service → Restaurant Service (reserve stock)
                                    ↓
                              Payment Service (charge)
                                    ↓
                              Order Service (confirm)
                                    ↓
                              Kafka (OrderCreated event)
```

### 12.3 Structured Logging

```java
@Slf4j
@Service
public class OrderService {
    public OrderResponse createOrder(...) {
        log.info("Creating order customerId={} restaurantId={} items={}",
                 customerId, restaurantId, items.size());
        // ...
        log.debug("Order created orderId={} sagaId={} traceId={}",
                  order.getId(), saga.getId(), MDC.get("traceId"));
    }
}
```

**JSON log format** (production):
```json
{
  "timestamp": "2026-09-16T13:00:00Z",
  "service": "order",
  "level": "INFO",
  "message": "Creating order",
  "customerId": 42,
  "restaurantId": 123,
  "traceId": "80f198ee56343ba864fe8b2a57d3bbc7",
  "spanId": "05d3c9c3a7f2a9d1"
}
```

---



## 13. Debugging Guide (Real-World Scenarios)

### Scenario 1: Order creation is slow

**Symptoms**: `POST /api/v1/orders` takes 10+ seconds.

**Investigation steps**:
1. Check gateway logs:
   ```bash
   tail -f /tmp/bhukkad-local/gateway.log | grep "POST /api/v1/orders"
   # Check if circuit breaker is open
   # Check if upstream is slow (response time in logs)
   ```

2. Check order service logs:
   ```bash
   tail -f /tmp/bhukkad-local/order.log | grep "Creating order"
   # Look for: "Saga step RESERVE_STOCK started"
   # Look for: "Calling restaurant service..."
   ```

3. Check database queries:
   ```sql
   SELECT query, mean_exec_time, calls
   FROM pg_stat_statements
   WHERE query LIKE '%orders%'
   ORDER BY mean_exec_time DESC
   LIMIT 10;
   ```

4. Check cache hit ratio:
   ```bash
   redis-cli info stats | grep keyspace_hits
   # Low hit ratio → cache is not warming up
   ```

5. Check circuit breakers:
   ```bash
   curl http://localhost:8092/actuator/circuitbreakers | jq
   # Look for state: "OPEN" or "HALF_OPEN"
   ```

**Common causes**:
- Restaurant service is down → circuit breaker open → retries with backoff
- Database connection pool exhausted → requests queue up
- Saga step (reserve stock) is slow → restaurant service DB query is slow

### Scenario 2: Search returns no results

**Symptoms**: `GET /api/v1/search?q=pizza` returns empty results.

**Investigation steps**:
1. Check if Elasticsearch is running:
   ```bash
   curl http://localhost:9200/_cluster/health
   ```

2. Check search service logs:
   ```bash
   tail -f /tmp/bhukkad-local/search.log | grep "Search"
   # Look for: "Elasticsearch search failed, falling back to PostgreSQL"
   ```

3. Check projection tables:
   ```sql
   SELECT COUNT(*) FROM restaurant_search;
   -- If 0, search index is empty → outbox sync is broken
   ```

4. Check outbox events:
   ```sql
   SELECT aggregate_type, event_type, status, COUNT(*)
   FROM outbox_events
   GROUP BY aggregate_type, event_type, status;
   -- Look for: FAILED or stuck PENDING events
   ```

### Scenario 3: Payment is double-charged

**Symptoms**: Customer charged twice for same order.

**Investigation steps**:
1. Check idempotency records:
   ```sql
   SELECT key, status, completed_at
   FROM idempotency_records
   WHERE key = 'order-789-payment-001';
   ```

2. Check if idempotency key was unique:
   - Client retried with SAME key → should return same payment
   - Client retried with DIFFERENT key → new payment created (expected behavior)

3. Check payment records:
   ```sql
   SELECT * FROM payments WHERE order_id = 789;
   -- Look for duplicate SETTLED payments
   ```

**Prevention**:
- Always use unique idempotency keys (orderId + attempt number)
- Client should generate UUID for each attempt

---



## 14. Summary Cheat Sheet

| API | Method | Path | Auth | Service | Key Tables | Pattern |
|-----|--------|------|------|---------|------------|---------|
| Register | POST | `/api/v1/auth/register` | None | identity | `users`, `customers` | Rate-limited, BCrypt hash |
| Login | POST | `/api/v1/auth/login` | None | identity | `users`, `refresh_tokens` | Rate-limited, JWT |
| List restaurants | GET | `/api/v1/restaurants/public` | None | restaurant | `restaurants` | Paginated, cached |
| Nearby restaurants | GET | `/api/v1/restaurants/public/nearby` | None | restaurant | `restaurants` (PostGIS) | Geo query, Redis cache |
| Create order | POST | `/api/v1/customers/{customerId}/orders` | Customer | order | `orders`, `order_items`, `saga_instances` | Saga sync (default) |
| Get orders | GET | `/api/v1/customers/{id}/orders` | Customer | order | `orders` | Paginated (no cache) |
| Add to cart | POST | `/api/v1/customers/{id}/cart/items` | Customer | order | `cart_items` | Server-side price |
| Clear cart | DELETE | `/api/v1/customers/{id}/cart` | Customer | order | `cart_items` | 204 No Content |
| Kitchen queue | GET | `/api/v1/orders/restaurant/{rid}/kitchen-queue` | Owner | order | `orders` | Paginated, status filter |
| Nearby restaurants | GET | `/api/v1/restaurants/public/nearby` | None | restaurant | `restaurants` | Geo query (in-JVM haversine, not cached) |

---



## 15. Next Steps for Interns

### Week 1: Foundation
1. **Run the stack locally**: `scripts/local-up.sh`
2. **Read `API_ARCHITECTURE_KT.md`** (this document)
3. **Trace one simple API**: `GET /api/v1/restaurants/public`
   - Find the controller
   - Find the repository
   - Run the SQL query manually in psql
   - Check the cache in Redis

### Week 2: Authentication
4. **Trace login flow**: `POST /api/v1/auth/login`
   - Read `IdentityController.login()`
   - Read `LoginLockoutService`
   - Generate a JWT manually (use `JwtService`)
   - Decode the JWT at jwt.io

5. **Trace protected endpoint**: `GET /api/v1/customers/{id}/orders`
   - Find `PrincipalGuard.requireSelfOrAdmin()`
   - Try accessing another user's orders (should get 403)

### Week 3: Order Creation Saga
6. **Trace full order flow**: `POST /api/v1/customers/{customerId}/orders`
    - Read `CustomerOrderController.create()`
    - Read `OrderService.createOrder()` (focus on saga start)
    - Find `SagaCoordinator`
    - Read `RestaurantClient.reserveStock()`
    - Read `PaymentServiceClient.charge()`

7. **Run the saga locally**: Set breakpoints in `OrderService`, place an order, watch the saga steps.

### Week 4: Advanced Patterns
8. **Read outbox pattern**: `OutboxClient`, `OutboxPollPublisher`
9. **Read caching**: `RedisCacheService`, `MenuCacheInvalidator`
10. **Read test suite**: `scripts/test-all-apis.py` — understand how each API is tested

### Week 5: Contribution
11. **Fix a bug**: Pick a failing test, fix the code, write a regression test.
12. **Add an API**: Add a new endpoint to an existing controller, follow the patterns in this doc.

---



*Document generated from bhukkad backend-server codebase (Java 17, Spring Boot 3.2, PostgreSQL 16, Redis 7, Kafka, Elasticsearch 8.x).*
