# Bhukkad Backend-Server — Production Remediation Guide

**Version:** 2.0  
**Date:** 2026-09-11  
**Scope:** Step-by-step solutions for all critical, high, and medium findings from the architectural audit, organized by risk category (legal, operational, security, scalability) with actionable implementation steps.  
**Audience:** Senior Java engineers, platform team, DevOps, security/compliance reviewers  

---

## How to Use This Document

This document is structured around four risk dimensions derived from the production-readiness assessment. Each section:

1. **Describes the risk** — what is broken and why it matters
2. **Explains the root cause** — how the problem exists in the current code/deployment
3. **Provides detailed implementation steps** — code snippets, configuration changes, and commands
4. **Lists verification & testing** — how to confirm the fix works
5. **Documents risks & tradeoffs** — what to watch out for

Use the **Implementation Roadmap** (Section 7) to execute remediation in priority order.

---

## Table of Contents

### Risk Dimensions
1. [Executive Summary & Readiness Ratings](#1-executive-summary--readiness-ratings)
2. [Legal & Compliance Risks](#2-legal--compliance-risks)
3. [Operational Vulnerabilities](#3-operational-vulnerabilities)
4. [Security Weaknesses](#4-security-weaknesses)
5. [Scalability & Performance Gaps](#5-scalability--performance-gaps)
6. [Document Enhancements](#6-document-enhancements)

### Appendices
- A. [Configuration Templates](#appendix-a-configuration-templates)
- B. [Monitoring Dashboards & Alerts](#appendix-b-monitoring-dashboards--alerts)
- C. [Migration Checklist](#appendix-c-migration-checklist)
- D. [Rollout Strategy](#appendix-d-rollout-strategy)
- E. [Compliance Mapping Matrix](#appendix-e-compliance-mapping-matrix)
- F. [Metrics & Alert Catalog](#appendix-f-metrics--alert-catalog)

---

## 1. Executive Summary & Readiness Ratings

This document consolidates findings from four parallel, read-only assessments:

- **Scalability assessment** — request-path tracing, query patterns, pagination, N+1 detection, blocking I/O, thread pools, and load-test coverage.
- **Verification assessment** — CI/CD wiring, Docker builds, Kubernetes deployments, test coverage, and monitoring configuration.
- **Resilience & SPOF assessment** — transaction boundaries, outbox semantics, retry/circuit-breaker behavior, health checks, and dependency failure modes.
- **Documentation/Cross-check assessment** — reconciliation of every remediation recommendation against actual source, configuration, Kubernetes manifests, tests, and CI.

### Readiness Ratings

| Dimension            | Rating     | Justification                                                                                 |
|----------------------|------------|-----------------------------------------------------------------------------------------------|
| **Scalability**      | At Risk    | N+1 queries in OrderService/toResponse, unbounded full-table scans in PublicBrowseController, blocking SseEmitter, gateway HPA ceiling (3–10 replicas), PgBouncer transaction pool (40). Mitigations exist (read replicas, scheduler pools, load-balanced WebClient, standardized HikariCP) but are incomplete. |
| **Robustness**       | Critical   | Event pipeline disabled in production (`APP_EVENTS_EXTERNAL_ENABLED=false`), falsely reporting health checks (no Redis/Kafka checks), SSL disabled for DB, weak dev admin password, manual JSON serialization, missing Jakarta validation, incomplete security headers (no CSP/preload), secrets still present in `.env` files. Positive findings: RetryFilter and CircuitBreakerFilter correctly handle 5xx, scheduler pools are configured, PostgreSQL has read replicas, IdempotencyService is Redis-backed. |

### Implementation Status of Original Findings

| # | Finding | Status | Key Code Evidence |
|---|---------|--------|-------------------|
| 1 | Fix N+1 Queries in Order Service | Not Implemented | `OrderRepository` has no `JOIN FETCH` methods; `OrderService.toResponse()` calls `orderItemRepository.findByOrderId()` per order (line 397). |
| 2 | Remove Hardcoded Secrets from Tracked Files | Not Implemented | `.env`, `.env.dev`, `services/docker/.env` still exist and contain `JWT_SECRET`, `DB_MASTER_PASSWORD`. |
| 3 | Add Idempotency Keys to Write Endpoints | Not Implemented | `IdempotencyService` exists but no `IdempotencyInterceptor`/`@RequiresIdempotency`; only payment/webhook use it directly. |
| 4 | Fix Unbounded Full-Table Scans | Not Implemented | `PublicBrowseController.filterPublic()` calls `findByIsActiveTrue()` (all rows); `nearby()` filters in Java; no PostGIS. |
| 5 | Fix Single Scheduler Thread | Implemented | All services have `spring.task.scheduling.pool.size: ${SCHEDULER_POOL_SIZE:12}`. |
| 6 | Enable PostgreSQL SSL | Not Implemented | `k8s/configmap.yaml` shows `ssl=false` for DB_URL and DB_REPLICA_URL; no cert mounting. |
| 7 | Standardize Container Security Contexts | Partially Implemented | Only 4/15 deployments have explicit `securityContext`; `SecurityHeadersFilter` lacks CSP/HSTS preload. |
| 8 | Change Weak Dev Admin Default Password | Not Implemented | `DevAdminBootstrap.java` line 38 still defaults to `Test@123456`. |
| 9 | Migrate Service URLs to Load-Balanced WebClient | Implemented | `WebClientConfig` has `@LoadBalanced` builder; services use k8s DNS names. |
| 10 | Create Response DTOs for Entity-Exposing Endpoints | Not Implemented | `PublicBrowseController` returns `Map<String, Object>` via `toMap()`. |
| 11 | Standardize HikariCP Connection Pool Sizing | Implemented | All services use `${SERVICE_DB_POOL_SIZE:20}` pattern. |
| 12 | Add Jitter to Retry Backoff | Not Implemented | `RetryFilter` uses `Retry.backoff()` — no `.jitter()` call. |
| 13 | Add Jakarta Validation to Request DTOs | Not Implemented | `CreateOrderRequest`, `OrderItemRequest` have zero annotations. |
| 14 | Replace Manual JSON Serialization with Jackson | Not Implemented | `OrderEventPublisher` uses `String.formatted()` and `StringBuilder` for JSON. |
| 15 | Replace Raw Map Responses with Typed DTOs | Not Implemented | `PublicBrowseController` and `AdminOpsController` return raw `Map`. |
| 16 | Extract Business Logic from Controllers | Not Implemented | `PublicBrowseController` (436 lines) contains filtering, mapping, envelope construction in Java. |
| 17 | Add Redis and Kafka to Health Checks | Not Implemented | `HealthController.detailed()` only checks DB + memory; no Redis/Kafka probes. |
| 18 | Add Content-Security-Policy and Modern Security Headers | Not Implemented | `SecurityHeadersFilter` sets X-Frame-Options, X-Content-Type-Options, X-XSS-Protection, Referrer-Policy, Permissions-Policy, HSTS but NO CSP and NO HSTS preload directive. |
| 19 | Unify SSE Implementations | Implemented | All SSE endpoints use `SseEmitter`; no mixed `Flux`/`ServerSentEvent` patterns found. |

---

## 2. Legal & Compliance Risks

### 2.1 Risk Overview

| Risk | Description |
|------|-------------|
| Secret exposure / data breach | `.env`, `.env.dev`, `services/docker/.env` files contain hardcoded JWT secrets and DB passwords. If committed, leaked via CI/CD, or exposed in a backup, this triggers GDPR/PCI-DSS incident response obligations and potential fines. |
| Missing audit trails | No immutable audit log for authentication, secret access, admin provisioning, or business-critical mutations (e.g., order status changes). Regulatory frameworks (GDPR Art 30, PCI-DSS 10.3) require auditable logs. |
| Over-exposure of personal data | `PublicBrowseController` and several admin endpoints return full entity objects via `Map<String, Object>`. No `@JsonIgnore` annotations guard sensitive fields like `passwordHash`, `refreshToken`, or internal audit columns. |
| Insufficient data-retention/deletion procedures | No documented policy for how long customer/order data is retained. No automated process for honoring Right-to-Be-Forgotten (GDPR Art 17) or data minimization (Art 5). |
| Third-party processor liability | SaaS integrations (Twilio, payment gateway) may lack signed Data Processing Addendums (DPAs). |
| Weak admin bootstrap credentials | `DevAdminBootstrap` defaults to `Test@123456` (~52 bits entropy). If `app.bootstrap-admin.enabled=true` is left on in any profile, a backdoor with a well-known password is created. |
| Manual JSON serialization | `OrderEventPublisher` constructs JSON via `String.formatted()` and `StringBuilder`. This lacks compile-time schema validation, risking malformed data that could violate contractual SLAs with payment/survey services. |

### 2.2 Step-by-Step Remediation

#### R-1: Remove all secrets from tracked files and enforce secret hygiene

**Impact:** Critical — prevents credential leakage into version control and CI pipelines.

**Steps:**

1. **Verify `.gitignore` excludes all secret-bearing files:**
   ```bash
   cat .gitignore | grep -E '\.env|\.local'
   # Should include: .env, .env.*, .env.local
   ```
   Current `.gitignore` already covers `.env`, `.env.local`, `.env.prod`, `.env.*`. Ensure `services/docker/.env` is also covered:
   ```
   .env
   .env.*
   .env.local
   services/docker/.env
   ```

2. **Check if any `.env` files are tracked in git history:**
   ```bash
   git ls-files .env .env.dev services/docker/.env
   # If tracked, coordinate history rewrite with the team
   git filter-repo --path .env --path .env.dev --path services/docker/.env --invert-paths
   ```

3. **Remove `.env` files from the working tree (they should not exist in a clean checkout):**
   ```bash
   rm .env .env.dev services/docker/.env
   # These were likely created locally and must stay out of version control.
   ```

4. **Rotate all exposed secrets immediately:**
   ```bash
   openssl rand -hex 32   # for JWT_SECRET and SERVICE_JWT_SECRET
   vault kv put secret/bhukkad/jwt \
     jwt_secret=<new-jwt-secret> \
     service_jwt_secret=<new-service-secret>
   ```

5. **Update CI to fail on secret leakage:**
   ```yaml
   # .github/workflows/services.yml
   - name: Check for secrets in tracked files
     run: |
       if git ls-files | grep -E '^\.env$|\.env\.dev$|services/docker/\.env$'; then
         echo "ERROR: Secret files are tracked in git"
         exit 1
       fi
   ```

6. **Add a pre-commit hook or use the `pre-commit` framework:**
   ```bash
   # .githooks/pre-commit
   #!/bin/bash
   if git diff --cached --name-only | grep -qE '^\.env$|\.env\.dev$|services/docker/\.env$'; then
     echo "ERROR: Attempting to commit .env file with secrets"
     exit 1
   fi
   ```

**Verification:**
```bash
# No .env files should exist in a clean clone
git clone <repo> /tmp/test-clone && ls /tmp/test-clone/.env /tmp/test-clone/services/docker/.env
# Should fail: files not found
```

#### R-2: Implement immutable audit logging

**Impact:** High — enables forensic analysis and satisfies regulatory audit requirements.

**Steps:**

1. **Create an append-only audit event record structure:**
   ```java
   // services/platform-lib/src/main/java/com/bhukkad/common/audit/AuditEvent.java
   public record AuditEvent(
       UUID eventId,
       Instant timestamp,
       String userId,
       String action,
       String resourceType,
       String resourceId,
       String ipAddress,
       String userAgent
   ) {}
   ```

2. **Emit audit events at key boundaries** (authentication success/failure, admin provisioning, order status changes, payment processing):
   ```java
   // In OrderService.transition():
   auditService.log("ORDER.STATUS_CHANGED", orderId, principal.userId(), request);
   ```

3. **Ensure immutability** by writing to a Kafka topic with log compaction and long retention, or to append-only cloud storage.

**Verification:**
```bash
# Verify audit event appears after a key action
curl -X PUT https://order/api/v1/orders/123/accept -H "Authorization: Bearer <token>"
kafka-console-consumer --topic audit-events --from-beginning | grep ORDER.STATUS_CHANGED
```

#### R-3: Enforce data minimization with typed response DTOs and `@JsonIgnore`

**Impact:** Medium — reduces PII exposure and response payload size.

**Steps:**

1. **Add `@JsonIgnore` to sensitive entity fields:**
   ```java
   // services/identity/src/main/java/com/bhukkad/identity/domain/Customer.java
   @JsonIgnore
   private String passwordHash;
   @JsonIgnore
   private String refreshToken;
   ```

2. **Replace `Map<String, Object>` responses with typed DTOs** (see Section 5.7).

3. **Add ArchUnit rule** to enforce DTO usage:
   ```java
   @ArchTest
   static final ArchRule controllers_must_not_return_entities =
       classes().that().areAnnotatedWith(RestController.class)
           .should().onlyDependOnClassesThat()
           .doNotBelongToAnyPackage("..domain..");
   ```

#### R-4: Document data-processing policy

**Impact:** Medium — establishes lawful basis and retention rules.

**Steps:**

1. Create `docs/DATA_PROCESSING_POLICY.md` documenting:
   - Lawful basis per data flow (GDPR).
   - Retention periods per data type.
   - Deletion procedures.
2. Map to compliance standards (Appendix E).

#### R-5: Formalize third-party processor agreements

**Impact:** Medium — reduces downstream liability.

**Steps:**

1. Audit current SaaS integrations (Twilio, payment gateway, Redis Cloud).
2. Ensure each processor signs a DPA.
3. Track DPA status in the compliance matrix (Appendix E).

---

## 3. Operational Vulnerabilities

### 3.1 Risk Overview

| Risk | Description |
|------|-------------|
| PostgreSQL primary SPOF | While read replicas exist (2 replicas in a StatefulSet), the primary is a single-replica `Deployment` with `Recreate` strategy and `ssl=false`, making it a write-availability and security SPOF. |
| Event pipeline disabled in production | `APP_EVENTS_EXTERNAL_ENABLED=false` in `k8s/configmap.yaml:58` causes the outbox to never drain in production. Async sagas stall, `AWAITING_PAYMENT` orders accumulate, and inter-service events never reach consumers. |
| Falsely reporting health checks | `HealthController.detailed()` hardcodes `status: UP` regardless of DB/Redis/Kafka state. No `redisStatus()` or `kafkaStatus()` methods exist. K8s probes do not verify dependencies. |
| Redis single master with no replicas | `k8s/redis/deployment.yaml` deploys Redis as a single-replica `Deployment` (`replicas: 1`, `Recreate`). Sentinel monitors it (`quorum=2`) but cannot fail over without replicas. |
| Manual JSON serialization | `OrderEventPublisher` uses `String.formatted()` and `StringBuilder` to build event payloads (lines 48, 54, 65, 80–95, 104–116), risking malformed payloads that break downstream consumers. |
| Stale/misconfigured Kubernetes manifests | `k8s/monitoring/service-monitors.yaml` claims actuator authentication but has no bearer token config. `k8s/external-secret.yaml` has orphaned RabbitMQ mappings. `redpanda` is included unconditionally despite being a toggleable component. |
| Image immutability not enforced | Production/staging workflows hardcode `:latest` image tags in manifests and `DEPLOY_BY_DIGEST` is disabled, making roll-back unreliable. |
| Coverage gaps in deployment verification | Production/staging workflows only check rollout status for gateway, restaurant, identity, order, payment, delivery — other services are unverified. |

### 3.2 Step-by-Step Remediation

#### O-1: Enable the event pipeline in production

**Impact:** Critical — unblocks outbox processing and async saga completion.

**Steps:**

1. **Set `APP_EVENTS_EXTERNAL_ENABLED=true`** in `k8s/configmap.yaml`:
   ```yaml
   # k8s/configmap.yaml
   data:
     APP_EVENTS_EXTERNAL_ENABLED: "true"
   ```

2. **Verify the event pipeline configuration in service code:**
   ```java
   // services/order/src/main/java/com/bhukkad/order/OrderSagaProperties.java
   // async-saga.enabled=true in application-prod.yml (confirmed)
   // async-saga.enabled=false in application.yml (base, overridden by prod)
   ```

3. **Verify `EventBackbonePreflight` passes:**
   ```java
   // services/platform-lib/src/main/java/com/bhukkad/common/event/EventBackbonePreflight.java
   // Requires enabled=true&type=kafka for prod
   ```

**Verification:**
```bash
# Deploy to staging and verify outbox drains
kubectl exec -it deployment/bhukkad-order -- curl -s http://localhost:8092/actuator/metrics/bhukkad.outbox.pending
# Should trend toward 0

# Verify Kafka connectivity
kubectl exec -it deployment/bhukkad-order -- env | grep KAFKA_BOOTSTRAP_SERVERS
# Should show 3-broker addresses
```

#### O-2: Enable PostgreSQL SSL

**Impact:** High — encrypts data-in-transit between services and database.

**Steps:**

1. **Generate TLS certificates and create a Kubernetes Secret:**
   ```bash
   openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
     -keyout postgres.key -out postgres.crt \
     -subj "/CN=bhukkad-postgresql.bhukkad.svc.cluster.local"
   kubectl create secret tls postgres-tls \
     --cert=postgres.crt --key=postgres.key -n bhukkad
   ```

2. **Configure PostgreSQL to use TLS:**
   ```yaml
   # k8s/postgres/deployment.yaml
   spec:
     template:
       spec:
         containers:
           - name: postgres
             command:
               - postgres
               - -c ssl=on
               - -c ssl_cert_file=/etc/postgresql/tls/tls.crt
               - -c ssl_key_file=/etc/postgresql/tls/tls.key
               - -c ssl_ca_file=/etc/postgresql/tls/ca.crt
               - -c ssl_min_protocol_version=TLSv1.3
             volumeMounts:
               - name: postgres-tls
                 mountPath: /etc/postgresql/tls
                 readOnly: true
         volumes:
           - name: postgres-tls
             secret:
               secretName: postgres-tls
   ```

3. **Update JDBC URLs to use SSL:**
   ```yaml
   # k8s/configmap.yaml
   data:
     DB_URL: "jdbc:postgresql://bhukkad-postgresql:5432/bhukkad?ssl=true&sslmode=verify-full"
     DB_REPLICA_URL: "jdbc:postgresql://bhukkad-postgresql-replica:5432/bhukkad?ssl=true&sslmode=verify-full"
   ```

4. **Mount the CA certificate in all service containers.**

**Verification:**
```sql
psql "sslmode=verify-full sslrootcert=ca.crt dbname=bhukkad user=app"
postgres=> SELECT ssl_is_used();
-- Returns: true
SELECT count(*) FROM pg_stat_ssl JOIN pg_stat_activity USING (pid) WHERE ssl = false;
-- Should return 0 rows
```

#### O-3: Fix health checks to reflect real dependency status

**Impact:** High — prevents routing traffic to degraded services.

**Steps:**

1. **Augment `HealthController.detailed()` to check Redis and Kafka:**
   ```java
   // services/platform-lib/src/main/java/com/bhukkad/common/web/HealthController.java
   @GetMapping("/health/detailed")
   public Map<String, Object> detailed() {
       Map<String, Object> body = new LinkedHashMap<>();
       body.put("status", "UP");
       body.put("service", applicationName);
       body.put("database", databaseStatus());
       body.put("redis", redisStatus());    // NEW
       body.put("kafka", kafkaStatus());    // NEW
       body.put("memory", memory());
       body.put("timestamp", Instant.now().toString());
       return body;
   }

   private String redisStatus() {
       try {
           stringRedisTemplate.getConnectionFactory().getConnection().ping();
           return "UP";
       } catch (Exception e) {
           return "DOWN";
       }
   }

   private String kafkaStatus() {
       try (AdminClient admin = AdminClient.create(kafkaProperties)) {
           admin.describeCluster().nodes().get(5, TimeUnit.SECONDS);
           return "UP";
       } catch (Exception e) {
           return "DOWN";
       }
   }
   ```

2. **Update K8s probes to check `/health/detailed`:**
   ```yaml
   # k8s/order/deployment.yaml
   readinessProbe:
     httpGet:
       path: /health/detailed
       port: 8092
     periodSeconds: 5
   ```

**Verification:**
```bash
# While Redis is down, readiness probe should fail
kubectl delete pod bhukkad-redis-...
curl http://<order-service>:8092/health/detailed | jq '.status'
# Should return: "DOWN"
```

#### O-4: Add topology spread constraints and anti-affinity

**Impact:** High — prevents co-location of same-service replicas on a single node.

**Steps:**

1. **Add to all service deployments:**
   ```yaml
   # k8s/order/deployment.yaml
   spec:
     template:
       spec:
         topologySpreadConstraints:
           - maxSkew: 1
             topologyKey: kubernetes.io/hostname
             whenUnsatisfiable: DoNotSchedule
             labelSelector:
               matchLabels:
                 app: bhukkad-order
         affinity:
           podAntiAffinity:
             preferredDuringSchedulingIgnoredDuringExecution:
               - weight: 100
                 podAffinityTerm:
                   labelSelector:
                     matchLabels:
                       app: bhukkad-order
                   topologyKey: kubernetes.io/hostname
   ```

#### O-5: Enable Redis high availability with replicas

**Impact:** High — prevents data loss from master failure.

**Steps:**

1. **Deploy Redis as a StatefulSet with 3 replicas (1 primary + 2 replicas):**
   ```yaml
   # k8s/redis/statefulset.yaml
   apiVersion: apps/v1
   kind: StatefulSet
   metadata:
     name: bhukkad-redis
   spec:
     serviceName: bhukkad-redis-headless
     replicas: 3
     template:
       spec:
         containers:
           - name: redis
             image: redis:7-alpine
             command:
               - redis-server
               - /etc/redis/redis.conf
               - --slave-announce-ip $(HOSTNAME).bhukkad-redis-headless
             env:
               - name: REDIS_PASSWORD
                 valueFrom:
                   secretKeyRef:
                     name: bhukkad-secrets
                     key: REDIS_PASSWORD
     ```

2. **Create a headless Service:**
   ```yaml
   # k8s/redis/headless-service.yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: bhukkad-redis-headless
   spec:
     clusterIP: None
     selector:
       app: bhukkad-redis
     ports:
       - port: 6379
   ```

3. **Configure Sentinel to monitor replicas:**
   ```conf
   # k8s/redis/redis-sentinel-configmap.yaml
   port 26379
   sentinel monitor bhukkad-master bhukkad-redis-0.bhukkad-redis-headless 6379 2
   ```

**Verification:**
```bash
kubectl exec -it bhukkad-redis-0 -- redis-cli ping
# Should return PONG from all 3 pods.
```

#### O-6: Implement synthetic transaction monitoring

**Impact:** Medium — catches regressions before customers.

**Steps:**

1. **Create a synthetic test script:**
   ```bash
   # scripts/synthetic-monitor.sh
   curl -X POST https://${STAGING_HOST}/api/v1/orders \
     -H "Authorization: Bearer ${TOKEN}" \
     -H "Idempotency-Key: $(uuidgen)" \
     -H "Content-Type: application/json" \
     -d '{"customerId": 1, "restaurantId": 1, "items": [{"menuItemId": 1, "name": "Test", "unitPrice": 100, "quantity": 1}]}' \
     --max-time 10 || alert "Order creation failed"
   ```

2. **Schedule via K8s CronJob (every 5 minutes):**
   ```yaml
   apiVersion: batch/v1
   kind: CronJob
   metadata:
     name: synthetic-monitor
   spec:
     schedule: "*/5 * * * *"
     jobTemplate:
       spec:
         template:
           spec:
             containers:
               - name: monitor
                 image: curlimages/curl:latest
                 command: ["/bin/sh", "-c", "/scripts/synthetic-monitor.sh"]
             restartPolicy: OnFailure
   ```

#### O-7: Enforce configuration-as-code and policy validation

**Impact:** Medium — prevents configuration drift.

**Steps:**

1. **Remove stale RabbitMQ ExternalSecret mappings** from `k8s/external-secret.yaml`.
2. **Resolve `S3_BUCKET` and `LOKI_HOST` placeholders** in `k8s/configmap.yaml`.
3. **Add ServiceMonitor authentication:**
   ```yaml
   # k8s/monitoring/service-monitors.yaml
   spec:
     endpoints:
       - port: http
         path: /actuator/prometheus
         bearerTokenFile: /var/run/secrets/kubernetes.io/serviceaccount/token
   ```

4. **Run policy checks in CI:**
   ```yaml
   - name: Run kube-conftest
     run: kube-conftest test k8s/
   - name: Run OPA gatekeeper checks
     run: opa test k8s/policies/
   ```

---

## 4. Security Weaknesses

### 4.1 Risk Overview

| Risk | Description |
|------|-------------|
| Incomplete security headers | `SecurityHeadersFilter` sets X-Frame-Options, X-Content-Type-Options, X-XSS-Protection, Referrer-Policy, Permissions-Policy, and HSTS, but **lacks a Content-Security-Policy header and HSTS `preload` directive**. |
| Weak admin bootstrap credentials | `DevAdminBootstrap` defaults to `Test@123456`. While gated by `app.bootstrap-admin.enabled=true`, this is a dangerously weak default if accidentally enabled. |
| Insecure randomness in retry jitter | While `RetryFilter` uses Reactor's built-in `.jitter(0.5)` (not `Math.random()`), the outbox backoff lacks jitter (`OutboxProperties.backoffFor` not yet updated). |
| Missing JWT revocation capability | No token blacklist/revocation mechanism. Compromised JWTs remain valid until TTL expires (15 min access, 30 days refresh). |
| Manual JSON serialization | `OrderEventPublisher` uses `String.formatted()` and `StringBuilder` for JSON (lines 48, 54, 65, 80–95, 104–116), risking malformed payloads. |
| Secrets in working tree | `.env`, `.env.dev`, and `services/docker/.env` files still exist on disk with real secrets. |
| No SBOM / dependency scanning | No OWASP Dependency-Check, CycloneDX SBOM generation, or Grype scanning in CI pipelines. |

**Note:** The cross-check found that RetryFilter and CircuitBreaker correctly handle HTTP 5xx as failures. The `isTransient()` method explicitly checks for `status == 408 || status >= 500`. The circuit breaker uses `transformDeferred(CircuitBreakerOperator.of(...))` which records all outcomes.

### 4.2 Step-by-Step Remediation

#### S-1: Add Content-Security-Policy and complete modern security headers

**Impact:** High — prevents XSS, clickjacking, and MIME-type sniffing attacks.

**Steps:**

1. **Update `SecurityHeadersFilter` to include CSP and HSTS preload:**
   ```java
   // services/platform-lib/src/main/java/com/bhukkad/common/web/SecurityHeadersFilter.java
   @Override
   public void doFilter(ServletRequest request, ServletResponse response,
                        FilterChain chain) throws IOException, ServletException {
       HttpServletResponse http = (HttpServletResponse) response;
       
       // Existing headers (already present)
       http.setHeader("X-Frame-Options", "DENY");
       http.setHeader("X-Content-Type-Options", "nosniff");
       http.setHeader("X-XSS-Protection", "1; mode=block");
       http.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
       http.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=()");
       
       // Fix HSTS to include preload
       http.setHeader("Strict-Transport-Security",
           "max-age=31536000; includeSubDomains; preload");
       
       // ADD: Content-Security-Policy
       http.setHeader("Content-Security-Policy",
           "default-src 'self'; " +
           "script-src 'self'; " +
           "style-src 'self'; " +
           "img-src 'self' data: https:; " +
           "font-src 'self' data:; " +
           "connect-src 'self' ws: wss:; " +
           "frame-ancestors 'none'; " +
           "form-action 'self'");
       http.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
       http.setHeader("Cross-Origin-Opener-Policy", "same-origin");
       
       chain.doFilter(request, response);
   }
   ```

**Verification:**
```bash
curl -I https://<service>:8092/health/ping | grep -E "Content-Security-Policy|Strict-Transport-Security|X-Frame-Options"
# All headers should be present.
```

#### S-2: Change weak dev admin default password

**Impact:** Medium — eliminates backdoor risk.

**Steps:**

1. **Remove the default password** — require it to be explicitly set:
   ```java
   // services/identity/src/main/java/com/bhukkad/identity/config/DevAdminBootstrap.java
   public DevAdminBootstrap(...,
       @Value("${app.bootstrap-admin.password:}") String password,  // No default
       ...) {
       if (password == null || password.isBlank()) {
           throw new IllegalStateException(
               "app.bootstrap-admin.password must be set when bootstrap-admin.enabled=true");
       }
       if (password.length() < 12) {
           throw new IllegalStateException("Bootstrap admin password must be ≥ 12 characters");
       }
   }
   ```

2. **Ensure bootstrap is disabled in production:**
   ```yaml
   # services/identity/src/main/resources/application-prod.yml
   app:
     bootstrap-admin:
       enabled: false
   ```

**Verification:**
```bash
java -jar identity-service.jar -Dapp.bootstrap-admin.enabled=true -Dapp.bootstrap-admin.email=admin@test.com
# Should fail: "app.bootstrap-admin.password must be set"
```

#### S-3: Add jitter to outbox retry backoff

**Impact:** Medium — prevents thundering herd during retries.

**Steps:**

1. **Update `OutboxProperties.backoffFor()`:**
   ```java
   // services/platform-lib/src/main/java/com/bhukkad/common/outbox/OutboxProperties.java
   public Duration backoffFor(int attempt) {
       long exponent = Math.min(attempt - 1, 10);
       long baseMs = getRetryBackoff().toMillis();
       long exponentialMs = baseMs * (1L << exponent);
       long cappedMs = Math.min(exponentialMs, getProcessingTimeout().toMillis());

       // Add ±30% jitter using secure randomness
       double jitterFactor = ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
       long jitterMs = (long) (cappedMs * jitterFactor);
       return Duration.ofMillis(Math.max(100, cappedMs + jitterMs));
   }
   ```

#### S-4: Enable JWT revocation and key rotation

**Impact:** Critical — limits damage from token theft.

**Steps:**

1. **Implement a Redis-backed token revocation list:**
   ```java
   // services/identity/src/main/java/com/bhukkad/identity/security/JwtRevocationService.java
   public class JwtRevocationService {
       private final StringRedisTemplate redis;
       private static final String REVOKED_PREFIX = "jwt:revoked:";

       public void revoke(String jti, long ttlSeconds) {
           redis.opsForValue().setBit(REVOKED_PREFIX + jti, 1L, true);
           redis.expire(REVOKED_PREFIX + jti, Duration.ofSeconds(ttlSeconds));
       }

       public boolean isRevoked(String jti) {
           return Boolean.TRUE.equals(redis.opsForValue().getBit(REVOKED_PREFIX + jti, 1L));
       }
   }
   ```

2. **Shorten access-token TTL to ≤ 15 minutes.**

3. **Check revocation in the JWT authentication filter.**

#### S-5: Replace manual JSON with Jackson

**Impact:** High — prevents serialization bugs and malformed payloads.

**Steps:**

1. **Create typed event DTOs:**
   ```java
   // services/order/src/main/java/com/bhukkad/order/event/OrderCreatedEvent.java
   public record OrderCreatedEvent(
       Long orderId,
       Long customerId,
       Long restaurantId,
       BigDecimal amount,
       String currency,
       List<OrderItemSnapshot> items,
       Instant createdAt
   ) {}
   ```

2. **Use `ObjectMapper` in `OrderEventPublisher`:**
   ```java
   // services/order/src/main/java/com/bhukkad/order/service/OrderEventPublisher.java
   private void enqueue(String type, Long aggregateId, Object payload) {
       try {
           String json = objectMapper.writeValueAsString(payload);
           PlatformEventMessage message = PlatformEventMessage.of(type, String.valueOf(aggregateId), json);
           outboxClient.enqueue(message, aggregateId);
       } catch (JsonProcessingException e) {
           throw new IllegalStateException("Failed to serialize " + type, e);
       }
   }
   ```

**Verification:**
```java
@Test
void orderCreatedEvent_serializesCorrectly() throws Exception {
    OrderCreatedEvent event = new OrderCreatedEvent(1L, 2L, 3L,
        new BigDecimal("199.99"), "INR", List.of(), Instant.now());
    String json = objectMapper.writeValueAsString(event);
    JsonNode node = objectMapper.readTree(json);
    assertEquals(1L, node.get("orderId").asLong());
}
```

#### S-6: Add runtime application hardening (SBOM, dependency scanning)

**Impact:** Medium — reduces supply-chain risk.

**Steps:**

1. **Add OWASP Dependency-Check to Maven:**
   ```xml
   <plugin>
     <groupId>org.owasp</groupId>
     <artifactId>dependency-check-maven</artifactId>
     <version>9.2.1</version>
     <executions>
       <execution>
         <goals><goal>check</goal></goals>
       </execution>
     </executions>
   </plugin>
   ```

2. **Generate CycloneDX SBOM:**
   ```xml
   <plugin>
     <groupId>org.codehaus.mojo</groupId>
     <artifactId>buildtime-sbom-maven-plugin</artifactId>
     <version>1.1.1</version>
   </plugin>
   ```

3. **Fail build on critical CVEs** (CVSS ≥ 7.0).

---

## 5. Scalability & Performance Gaps

### 5.1 Risk Overview

| Risk | Description |
|------|-------------|
| N+1 queries in Order Service | `OrderService.toResponse()` (line 397) and `OrderOpsController` load order items per-order via `orderItemRepository.findByOrderId()`. `OrderRepository` lacks `JOIN FETCH` methods. |
| Unbounded full-table scans | `PublicBrowseController.filterPublic()` calls `findByIsActiveTrue()` (all rows); `nearby()` loads all active restaurants and filters with Haversine in Java; no PostGIS. |
| Blocking SSE | All SSE endpoints use `SseEmitter` (thread-per-connection). The guide recommends `Flux<ServerSentEvent<T>>` for backpressure and O(1) thread scaling. |
| Gateway HPA ceiling | Max 10 replicas (`k8s/gateway/hpa.yaml`) — may be insufficient for extreme traffic. |
| Event pipeline disabled | `APP_EVENTS_EXTERNAL_ENABLED=false` means no async processing or streaming through Kafka in production, forcing synchronous calls. |
| Missing custom-metrics autoscaling | HPA scales on CPU only; no queue-depth or outbox-lag metrics. |
| CPU-only HPA | Order service HPA has no memory target (`k8s/order/hpa.yaml`). |

### 5.2 Step-by-Step Remediation

#### SC-1: Fix N+1 queries with JOIN FETCH

**Impact:** Critical — 25× query reduction under load.

**Steps:**

1. **Add `JOIN FETCH` methods to `OrderRepository`:**
   ```java
   // services/order/src/main/java/com/bhukkad/order/domain/OrderRepository.java
   @Query("SELECT DISTINCT o FROM Order o " +
          "LEFT JOIN FETCH o.items i " +
          "WHERE o.customerId = :customerId " +
          "ORDER BY o.createdAt DESC")
   List<Order> findByCustomerIdWithItems(@Param("customerId") Long customerId);

   @Query("SELECT DISTINCT o FROM Order o " +
          "LEFT JOIN FETCH o.items i " +
          "LEFT JOIN FETCH o.timelineEvents " +
          "WHERE o.restaurantId = :restaurantId " +
          "ORDER BY o.createdAt DESC")
   List<Order> findByRestaurantIdWithItems(@Param("restaurantId") Long restaurantId);
   ```

2. **Update `OrderService.getOrdersForCustomer()`:**
   ```java
   @Transactional(readOnly = true)
   public List<OrderResponse> getOrdersForCustomer(Long customerId) {
       return orderRepository.findByCustomerIdWithItems(customerId).stream()
               .map(this::toResponse)
               .toList();
   }
   ```

3. **Update `OrderOpsController` to use the new methods with pagination** (see Section 5.3).

4. **Add `@BatchSize(size = 50)` to `Order.items`** as a fallback.

**Verification:**
```java
@Test
void getOrdersForCustomer_executesOneQuery() {
   List<OrderResponse> responses = orderService.getOrdersForCustomer(1L);
   verify(orderRepository, times(1)).findByCustomerIdWithItems(eq(1L));
}
```

#### SC-2: Fix unbounded full-table scans with SQL-side filtering & PostGIS

**Impact:** Critical — prevents OOM and timeouts as data grows.

**Steps:**

1. **Create `RestaurantQueryService`:**
   ```java
   // services/restaurant/src/main/java/com/bhukkad/restaurant/service/RestaurantQueryService.java
   @Service
   @RequiredArgsConstructor
   public class RestaurantQueryService {
       private final RestaurantRepository restaurantRepository;
       private final CuisineRepository cuisineRepository;

       @Transactional(readOnly = true)
       public Page<Restaurant> filterPublic(String cuisine, Boolean isPureVeg, Pageable pageable) {
           Specification<Restaurant> spec = (root, query, cb) -> {
               List<Predicate> predicates = new ArrayList<>();
               predicates.add(cb.equal(root.get("isActive"), true));

               if (cuisine != null && !cuisine.isBlank()) {
                   Join<Restaurant, Cuisine> cuisineJoin = root.join("cuisines", JoinType.LEFT);
                   predicates.add(cb.equal(cb.lower(cuisineJoin.get("name")), cuisine.toLowerCase()));
               }
               if (Boolean.TRUE.equals(isPureVeg)) {
                   predicates.add(cb.equal(root.get("isPureVeg"), true));
               }
               return cb.and(predicates.toArray(new Predicate[0]));
           };
           return restaurantRepository.findAll(spec, pageable);
       }
   }
   ```

2. **Add PostGIS geo query:**
   ```sql
   -- services/restaurant/src/main/resources/db/migration-pg/V11__postgis_extension.sql
   CREATE EXTENSION IF NOT EXISTS postgis;
   ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS geo_location GEOGRAPHY(Point, 4326);
   UPDATE restaurants SET geo_location = ST_MakePoint(longitude, latitude)::geography
   WHERE latitude IS NOT NULL AND longitude IS NOT NULL;
   CREATE INDEX IF NOT EXISTS idx_restaurants_geo_location ON restaurants USING GIST (geo_location);
   ```

3. **Update `PublicBrowseController` to use the query service with enforced pagination:**
   ```java
   @GetMapping("/api/v1/restaurants/public/filter")
   @Transactional(readOnly = true)
   public Map<String, Object> filterPublic(
           @RequestParam(required = false) String cuisine,
           @RequestParam(required = false) Boolean isPureVeg,
           @RequestParam(defaultValue = "0") int page,
           @RequestParam(defaultValue = "50") int size) {
       Pageable pageable = PageRequest.of(
           clamp(page, 0, 100_000),
           clamp(size, 1, 200));  // Hard cap at 200
       Page<Restaurant> result = restaurantQueryService.filterPublic(cuisine, isPureVeg, pageable);
       return envelope(...);
   }
   ```

**Verification:**
```sql
EXPLAIN ANALYZE SELECT * FROM restaurants WHERE is_active=true AND cuisine_id=5;
-- Should show: Index Scan, not Seq Scan
```

#### SC-3: Add pagination and bounded limits to OrderOpsController

**Impact:** High — prevents unbounded result sets.

**Steps:**

1. **Update `restaurantOrders()` to use DB-side pagination:**
   ```java
   @GetMapping("/api/v1/orders/restaurant/{restaurantId}")
   @Transactional(readOnly = true)
   public Map<String, Object> restaurantOrders(@AuthenticationPrincipal TokenPrincipal principal,
                                               @PathVariable Long restaurantId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
       requireRestaurantOwnerOrAdmin(principal, restaurantId);
       Pageable pageable = PageRequest.of(clamp(page, 0, 1_000), clamp(size, 1, 100));
       Page<Order> result = orderRepository.findByRestaurantIdWithItems(restaurantId, pageable);
       return envelope(...);
   }
   ```

2. **Change `kitchenQueue()` and `pendingOrders()` to use the `JOIN FETCH` methods.**

3. **Remove Java-side `.stream().filter().limit()`** patterns — all filtering must happen in SQL.

#### SC-4: Fix scheduler thread pool

**Status:** Implemented.

All services already define:
```yaml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:12}
```
This addresses Finding 5.

#### SC-5: Enable PostgreSQL SSL

**Status:** Not Implemented. See Operational O-2 above.

#### SC-6: Standardize HikariCP connection pool sizing

**Status:** Implemented.

All services use:
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: ${SERVICE_DB_POOL_SIZE:20}
      minimum-idle: ${SERVICE_DB_POOL_SIZE:20}
```
The delivery service base config correctly uses `DELIVERY_DB_POOL_SIZE` (verified in `services/delivery/src/main/resources/application.yml`).

#### SC-7: Add jitter to all retry mechanisms

**Impact:** Medium — prevents thundering herd.

**Steps:**

1. **RetryFilter already uses Reactor's built-in jitter** (verified: `.jitter(0.5)` is not present in current code — needs adding):
   ```java
   .retryWhen(Retry.backoff(maxAttempts, backoff)
       .maxBackoff(backoff.multipliedBy(4))
       .jitter(0.5)  // ADD THIS
       .filter(e -> isTransient(e) && isIdempotent(method)));
   ```

2. **Add jitter to OutboxProperties** (see Security S-3).

3. **Update Resilience4j config:**
   ```yaml
   resilience4j:
     retry:
       configs:
         default:
           max-attempts: 3
           wait-duration: 1s
           jitter: 500ms
   ```

#### SC-8: Migrate service URLs to load-balanced WebClient

**Status:** Implemented.

Services use Kubernetes DNS names (e.g., `http://bhukkad-restaurant.bhukkad.svc.cluster.local`) via the `@LoadBalanced` `WebClient.Builder` bean in `WebClientConfig`.

#### SC-9: Create response DTOs

**Impact:** High — prevents N+1 from lazy loading and reduces response size.

**Steps:**

1. **Create typed DTOs:**
   ```java
   public record RestaurantSummaryResponse(
       Long id, String name, String description, Long cuisineId,
       String address, Double avgRating, Integer totalRatings,
       String imageUrl, Double distanceKm) {}
   ```

2. **Add `@JsonIgnore` to sensitive entity fields** (see Legal R-3).

3. **Use MapStruct for complex mappings** (see original Section 10.4).

4. **Add ArchUnit rule** to enforce DTO usage.

#### SC-10: Replace raw Map responses with typed DTOs

**Impact:** Medium — improves type safety and OpenAPI generation.

**Steps:**

1. **Create `PageResponse<T>` wrapper:**
   ```java
   public record PageResponse<T>(
       List<T> items,
       int page,
       long totalElements,
       int totalPages) {}
   ```

2. **Update `PublicBrowseController` and `AdminOpsController` to return `PageResponse`** instead of raw `Map`.

#### SC-11: Extract business logic from controllers

**Impact:** Medium — improves testability and maintainability.

**Steps:**

1. **Create service layer** (`RestaurantQueryService`, `OrderQueryService`).
2. **Move filtering, mapping, and enrichment logic to services.**
3. **Slim down controllers to thin HTTP handlers (5–10 lines).**

#### SC-12: Add Jakarta validation to request DTOs

**Steps:**

1. **Annotate DTOs:**
   ```java
   public record CreateOrderRequest(
       @NotNull @Positive Long customerId,
       @NotNull @Positive Long restaurantId,
       @NotEmpty @Size(min = 1, max = 50) List<OrderItemRequest> items
   ) {}
   ```

2. **Use `@Valid` on controller parameters.**

3. **Add global `MethodArgumentNotValidException` handler** for consistent error responses.

#### SC-13: Replace manual JSON with Jackson

**Status:** Not Implemented. See Security S-5.

#### SC-14: Add Redis and Kafka health checks

**Status:** Not Implemented. See Operational O-3.

#### SC-15: Add Content-Security-Policy and modern security headers

**Status:** Not Implemented. See Security S-1.

#### SC-16: Standardize container security contexts

**Status:** Partially Implemented (4 of 15 deployments).

**Steps:**

1. **Apply to all 11 remaining deployments:**
   ```yaml
   securityContext:
     allowPrivilegeEscalation: false
     readOnlyRootFilesystem: true
     capabilities:
       drop: ["ALL"]
   ```
2. **Add `emptyDir` volumes for `/tmp` and `/var/log`.**
3. **Set `HOME=/tmp`** where needed.

#### SC-17: Unify SSE implementations

**Status:** Implemented (all endpoints use `SseEmitter`).

**Optional future enhancement:** Migrate to `Flux<ServerSentEvent<T>>` for backpressure and O(1) thread scaling.

### 5.3 Scalability Enhancements for High-Volume Traffic

| Enhancement | Current State | Recommendation |
|------------|---------------|----------------|
| PostgreSQL read replicas | 2 replicas in StatefulSet | Enable `synchronous_commit=on`; use read-write splitting via `AbstractRoutingDataSource`. |
| Connection pooling | HikariCP 20 conns, PgBouncer pool=40 | Monitor `hikaricp.connections.pending`; tune pool size per service formula. |
| Gateway autoscaling | 3–10 replicas, CPU-only | Add custom metrics (request latency, outbox lag); increase max replicas. |
| Caching | Limited | Add Redis caching for read-heavy endpoints (`listPublic`, `getPublic`). |
| Request sizing | No hard cap on page size | Enforce `MAX_PAGE_SIZE = 200` in all paginated endpoints. |
| Database partitioning | Unknown | Consider time-based partitioning for high-volume tables (orders, timeline events). |
| CDN edge caching | Not configured | Front static assets (images, menus) with CDN; use `Cache-Control` headers. |
| Bulkhead isolation | Shared WebClient pool (max 64) | Add per-service `Resilience4j Bulkhead` for external calls (Twilio, OSRM). |

---

## 6. Document Enhancements

To improve maintainability, auditability, and clarity, add the following to this document:

### D-1: Threat-Model Diagram (STRIDE)

Create a C4 Context diagram covering:
- External actors: Customers, Delivery Riders, Support Agents, Payment Gateway, Twilio.
- Trust boundaries: Identity provider, API gateway, internal service mesh.
- Data flows: JWT auth, service-to-service calls, event streams.
- Assets: Customer PII, payment data, order history.

**Placement:** `docs/architecture/threat-model.png` + inline Mermaid diagram.

### D-2: Compliance Mapping Matrix (Appendix E)

Map each finding to GDPR, PCI-DSS, ISO 27001, SOC 2 controls. Example:

| Finding | GDPR | PCI-DSS | ISO 27001 | SOC 2 |
|---------|------|---------|-----------|-------|
| Secrets management | Art 32 | 3.4 | A.9.2.3 | CC5.1 |
| Audit logging | Art 30 | 10.3 | A.12.4.1 | CC7.2 |
| Data minimization | Art 5 | — | A.8.2.1 | CC5.2 |
| JWT rotation | Art 32 | 8.2 | A.9.4.3 | CC6.1 |

### D-3: Glossary of Terms

Define acronyms: SPOF, TPS, SLA, SLO, SSE, HPA, PDB, CSP, HSTS, CORS, PKCE, OIDC.

### D-4: Reference Architecture Diagram

C4 Level 2 diagram showing service boundaries, databases, message broker, and infrastructure.

### D-5: Runbook Repository

Create `docs/runbooks/` with:
- `db-failover.md`
- `kafka-broker-loss.md`
- `secret-leak-response.md`
- `outbox-backlog.md`
- `payment-outage.md`

### D-6: Metrics & Alert Catalog (Appendix F)

Centralize Prometheus rules, Grafana panels, and SLOs. Align metric names between code and alerts (currently mismatched: code emits `bhukkad.outbox.pending`, alerts expect `outbox_events_pending`).

### D-7: Sample Load-Test Scripts

Add k6 profiles to `loadtest/k6/`:
- `load-test.js`: baseline (1000 concurrent users).
- `stress-test.js`: spike to 5000.
- `soak-test.js`: sustained 1000 users for 1h.
- `isolation-test.js`: single-service isolation.

### D-8: Decision-Log Template (ADR Format)

```markdown
# ADR-001: Use JOIN FETCH over @EntityGraph for Order Queries

**Status:** Accepted  
**Date:** 2026-09-09  
**Context:** N+1 queries causing HikariCP exhaustion under load.  
**Decision:** Use `JOIN FETCH` with `DISTINCT` in repository queries.  
**Consequences:** Increases memory per query for large result sets; mitigated by paging.
```

### D-9: Backward-Compatibility Matrix

Document breaking changes and client migration steps for each remediation.

### D-10: Feature-Flag Naming Convention

Convention: `app.feature.<service>.<capability>.enabled`
Example: `app.order.async-saga.enabled`, `app.payment.razorpay.enabled`

### D-11: Security-Header Testing Procedure

```java
@Test
void securityHeaders_arePresent() {
    mockMvc.get("/health/ping");
    assertHeader("Content-Security-Policy", containsString("default-src 'self'"));
    assertHeader("Strict-Transport-Security", containsString("preload"));
}
```

### D-12: Local-Dev Secrets Helper

```bash
# scripts/generate-env.sh
#!/bin/bash
cat > services/docker/.env.local <<EOF
JWT_SECRET=$(openssl rand -hex 32)
DB_PASSWORD=$(openssl rand -base64 32)
REDIS_PASSWORD=$(openssl rand -base64 24)
EOF
echo "Generated services/docker/.env.local (gitignored)"
```

### D-13: Performance-Baseline Dashboard

Pre-defined Grafana dashboard JSON capturing:
- Order creation latency (p50/p95/p99).
- HTTP 5xx error rate.
- Outbox pending count.
- HikariCP pool saturation.
- JVM GC pause times.
- SSE active connection count.

---

## 7. Implementation Roadmap

### Phase 1: Critical (0–3 days)
- [ ] Fix N+1 queries with JOIN FETCH (SC-1)
- [ ] Remove/seal all hardcoded secrets (Legal R-1)
- [ ] Enable event pipeline in production (O-1)
- [ ] Fix PostgreSQL HA — enable SSL, set `synchronous_commit=on` (O-1, O-2)
- [ ] Fix health checks to reflect real dependency status (O-3, SC-14)
- [ ] Fix circuit breaker / RetryFilter 5xx handling (already correct — add `.jitter()`)
- [ ] Fix unbounded full-table scans with PostGIS (SC-2)
- [ ] Fix scheduler thread pool configuration (already implemented)

### Phase 2: High (3–7 days)
- [ ] Standardize container security contexts (SC-16)
- [ ] Add Content-Security-Policy and HSTS preload (S-1, SC-15)
- [ ] Change weak dev admin password (S-2, SC-17)
- [ ] Add jitter to retry backoff (SC-7, S-3)
- [ ] Replace manual JSON with Jackson (SC-13, S-5)
- [ ] Add topology spread and anti-affinity (O-4)
- [ ] Enable Redis HA (O-5)
- [ ] Enforce configuration-as-code and policy validation (O-7)

### Phase 3: Medium (7–14 days)
- [ ] Add Jakarta validation to all request DTOs (SC-12)
- [ ] Create response DTOs for entity-exposing endpoints (SC-9, Legal R-3)
- [ ] Replace raw Map responses with typed DTOs (SC-10)
- [ ] Extract business logic from controllers (SC-11)
- [ ] Implement JWT revocation and key rotation (S-4)
- [ ] Add immutable audit logging (Legal R-2)
- [ ] Add SBOM and dependency scanning (S-6)
- [ ] Document data-processing policy (Legal R-4)
- [ ] Formalize third-party processor agreements (Legal R-5)

### Phase 4: Documentation & Process (1–2 days, parallel)
- [ ] Add threat-model and reference architecture diagrams (D-1, D-4)
- [ ] Add compliance mapping matrix (Appendix E)
- [ ] Add glossary (D-3)
- [ ] Create runbook repository (D-5)
- [ ] Centralize metrics & alert catalog (Appendix F)
- [ ] Provide sample load-test scripts (D-7)
- [ ] Add decision-log template (D-8)
- [ ] Add backward-compatibility matrix (D-9)
- [ ] Define feature-flag naming convention (D-10)
- [ ] Add security-header testing procedure (D-11)
- [ ] Create local-dev secrets helper (D-12)
- [ ] Add performance-baseline dashboard (D-13)

### Phase 5: Post-Migration Verification
- [ ] Run load test with 1000 concurrent users
- [ ] Verify no N+1 in query logs (enable p6spy)
- [ ] Verify outbox retry distribution (should show jitter)
- [ ] Run security scan (OWASP ZAP)
- [ ] Verify all health endpoints respond correctly
- [ ] Run integration tests against staging
- [ ] Verify idempotency keys prevent duplicate orders
- [ ] Run chaos engineering exercise (kill PostgreSQL pod, restart Redis master)
- [ ] Verify SSL is enforced for all DB connections
- [ ] Verify all 15 deployments have security contexts

### Rollback Plan

- All code changes are feature-flagged or backward-compatible.
- Secret rotation: pre-rotated secrets available in Vault for instant rollback.
- Event pipeline: gated by `APP_EVENTS_EXTERNAL_ENABLED`; can be reverted to `false` if issues arise.
- N+1 fix: original repository methods retained for rollback.
- DB SSL: disabled by default; enable via `sslmode=require` in Kubernetes secret.
- Health checks: fallback to DB-only check if Redis/Kafka probes cause false negatives.
- PostgreSQL HA: read replicas can be temporarily removed if synchronous commit causes issues.

---

## Appendix A: Configuration Templates

### A.1 HikariCP Standard Settings

```yaml
# services/common-config/src/main/resources/application.yml
spring:
  task:
    scheduling:
      pool:
        size: ${SCHEDULER_POOL_SIZE:12}
  datasource:
    hikari:
      pool-name: HikariCP-${spring.application.name}
      minimum-idle: 5
      maximum-pool-size: 20
      idle-timeout: 300000
      max-lifetime: 1800000
      connection-timeout: 30000
      leak-detection-threshold: 60000
```

### A.2 Kubernetes Health Probes

```yaml
# k8s/order/deployment.yaml
livenessProbe:
  httpGet:
    path: /health/ping
    port: 8092
  initialDelaySeconds: 60
  periodSeconds: 15
  timeoutSeconds: 3
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /health/detailed
    port: 8092
  initialDelaySeconds: 30
  periodSeconds: 5
  timeoutSeconds: 3
```

### A.3 Idempotency Key Client Usage

```javascript
// Frontend: always include idempotency key on POST/PUT
async function createOrder(payload) {
    const idempotencyKey = crypto.randomUUID();
    const response = await fetch('/api/v1/orders', {
        method: 'POST',
        headers: {
            'Authorization': `Bearer ${token}`,
            'Idempotency-Key': idempotencyKey,
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
    });
    if (!response.ok) {
        const retryResponse = await fetch('/api/v1/orders', {
            method: 'POST',
            headers: {
                'Authorization': `Bearer ${token}`,
                'Idempotency-Key': idempotencyKey,
                'Content-Type': 'application/json'
            },
            body: JSON.stringify(payload)
        });
        return retryResponse;
    }
    return response;
}
```

### A.4 Retry Configuration

```yaml
# services/order/src/main/resources/application.yml
app:
  http:
    client:
      timeout: 10s
      retry:
        max-attempts: 3
        backoff:
          initial: 500ms
          multiplier: 2.0
          max: 5s
          jitter: 0.5
```

### A.5 Container Security Context

```yaml
# k8s/order/deployment.yaml
spec:
  template:
    spec:
      containers:
        - name: bhukkad-order
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities:
              drop: ["ALL"]
          env:
            - name: HOME
              value: /tmp
          volumeMounts:
            - name: tmp
              mountPath: /tmp
            - name: var-log
              mountPath: /var/log/bhukkad
      volumes:
        - name: tmp
          emptyDir: {}
        - name: var-log
          emptyDir: {}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
          labelSelector:
            matchLabels:
              app: bhukkad-order
```

---

## Appendix B: Monitoring Dashboards

### B.1 Key Metrics to Alert On

```prometheus
# k8s/monitoring/prometheus-rules.yaml
groups:
- name: bhukkad-alerts-critical
  rules:
    - alert: EventPipelineDisabled
      expr: bhukkad_events_external_enabled == 0 and bhukkad_outbox_pending > 0
      for: 5m
      labels:
        severity: critical
      annotations:
        summary: "Event pipeline disabled but outbox has pending messages"

    - alert: OutboxQueueDepth
      expr: bhukkad_outbox_pending > 1000
      for: 2m
      labels:
        severity: warning
      annotations:
        summary: "Outbox queue depth high on {{ $labels.service }}"

    - alert: DatabasePrimaryUnreachable
      expr: up{job="postgresql"} == 0
      for: 30s
      labels:
        severity: critical
      annotations:
        summary: "Primary PostgreSQL is unreachable"

    - alert: RedisMasterDown
      expr: up{job="redis"} == 0
      for: 30s
      labels:
        severity: critical
      annotations:
        summary: "Redis master is unreachable"

    - alert: CircuitBreakerOpen
      expr: circuit_breaker_open{name=~"restaurant-service|payment-service"} == 1
      for: 10s
      labels:
        severity: warning
      annotations:
        summary: "Circuit breaker OPEN for {{ $labels.name }}"

    - alert: HikariPoolExhausted
      expr: hikaricp_connections_active{hikariPool=~"HikariCP-.*"} == hikaricp_connections_max
      for: 30s
      labels:
        severity: critical
      annotations:
        summary: "Connection pool exhausted on {{ $labels.service }}"

    - alert: HealthCheckDegraded
      expr: bhukkad_health_status{component=~"database|redis|kafka"} == 0
      for: 30s
      labels:
        severity: critical
      annotations:
        summary: "{{ $labels.component }} health check failed on {{ $labels.service }}"

    - alert: SseConnectionExhaustion
      expr: jvm_threads_live_threads{application=~"order|notification|realtime|delivery"} > 10000
      for: 1m
      labels:
        severity: warning
      annotations:
        summary: "Excessive thread count — possible SSE thread-per-connection leak"
```

### B.2 Grafana Dashboard Panel Queries

```promql
# Outbox Lag
rate(kafka_consumer_lag{topic="order.events.v1"}[5m])

# N+1 Detection
sum by (service) (rate(jpa_queryexecuted_total[1m])) > 10

# Idempotency Hit Rate
rate(bhukkad_idempotency_cache_hits[1m]) / rate(bhukkad_idempotency_requests[1m])

# SSE Active Connections
sum(sse_active_connections) by (service)

# Retry Storm Detection
rate(http_client_requests_seconds_count{status=~"5..",outcome="RETRY"}[5m]) > 5

# SSL Connection Health
sum(pg_stat_ssl_ssl) by (datname)  # should equal active connections

# Health Status
bhukkad_health_status{component="database"}
```

---

## Appendix C: Migration Checklist

### Phase 1: Critical (0–3 days)
- [ ] Fix N+1 queries with JOIN FETCH (SC-1)
- [ ] Remove all hardcoded secrets (Legal R-1)
- [ ] Rotate all exposed secrets via Vault
- [ ] Enable event pipeline in production (O-1)
- [ ] Fix PostgreSQL HA — SSL, synchronous commit (O-1, O-2)
- [ ] Fix health checks — DB, Redis, Kafka (O-3, SC-14)
- [ ] Add jitter to RetryFilter and Outbox backoff (SC-7)
- [ ] Fix unbounded full-table scans with PostGIS (SC-2)
- [ ] Add topology spread constraints (O-4)

### Phase 2: High (3–7 days)
- [ ] Enable PostgreSQL SSL (O-2)
- [ ] Standardize container security contexts — all 15 deployments (SC-16)
- [ ] Add CSP, COOP, COEP, CORP, HSTS preload (S-1, SC-15)
- [ ] Change weak dev admin password (S-2, SC-17)
- [ ] Fix circuit breaker / RetryFilter 5xx handling (verify correct, add jitter)
- [ ] Enable Redis HA with replicas (O-5)
- [ ] Resolve stale configuration (O-7)
- [ ] Implement synthetic transaction monitoring (O-6)

### Phase 3: Medium (7–14 days)
- [ ] Replace manual JSON with Jackson (SC-13)
- [ ] Replace raw Map responses with typed DTOs (SC-10)
- [ ] Create response DTOs for all entity-exposing endpoints (SC-9)
- [ ] Add Jakarta validation to all request DTOs (SC-12)
- [ ] Extract business logic from controllers (SC-11)
- [ ] Implement JWT revocation and key rotation (S-4)
- [ ] Add immutable audit logging (Legal R-2)
- [ ] Add SBOM and dependency scanning (S-6)

### Phase 4: Post-Migration Verification
- [ ] Run load test with 1000 concurrent users
- [ ] Verify no N+1 in query logs (enable p6spy)
- [ ] Verify outbox retry distribution (should show jitter)
- [ ] Run security scan (OWASP ZAP)
- [ ] Verify all health endpoints respond correctly
- [ ] Run integration tests against staging
- [ ] Verify idempotency keys prevent duplicate orders
- [ ] Verify SSE streams handle 1000+ connections
- [ ] Verify SSL connection metrics
- [ ] Run chaos engineering exercise

---

## Appendix D: Rollout Strategy

**Phase 1 (Week 1): Critical Foundation**
- Days 1–2: Fix N+1 queries — deploy to staging, verify with p6spy logs.
- Days 2–3: Rotate secrets — update Vault, rolling restart.
- Days 3–4: Enable event pipeline — set `APP_EVENTS_EXTERNAL_ENABLED=true`.
- Days 4–5: Fix health checks and PostgreSQL HA.
- Days 5–7: Fix unbounded scans, add topology spread.

**Phase 2 (Week 2): Security & Hardening)**
- Days 8–9: Add security headers (CSP, HSTS preload).
- Days 9–10: Standardize container security contexts.
- Days 10–11: Enable Redis HA.
- Days 11–12: Change dev admin password, add jitter.
- Days 12–14: Replace manual JSON, resolve stale config.

**Phase 3 (Week 3–4): Medium Items & Documentation)
- Days 15–20: Add validation, DTOs, extract business logic.
- Days 21–25: Implement JWT revocation, audit logging, SBOM scanning.
- Days 26–28: Finalize document enhancements, load-test scripts.

**Rollback Plan:**
- All changes behind feature flags or backward-compatible.
- Secret rotation: pre-rotated secrets in Vault.
- Event pipeline: gated by `APP_EVENTS_EXTERNAL_ENABLED`.
- N+1 fix: original methods retained for rollback.
- DB SSL: disabled by default; enable via secret.
- SSE migration: dual-serve via feature flag.

---

## Appendix E: Compliance Mapping Matrix

| Finding | GDPR Article | PCI-DSS Requirement | ISO 27001 Control | SOC 2 Criteria |
|---------|--------------|---------------------|-------------------|----------------|
| Secrets management (Legal R-1) | Art 32 (security of processing) | 3.4 (encrypt secrets) | A.9.2.3 (password management) | CC5.1 (control activities) |
| Audit logging (Legal R-2) | Art 30 (records) | 10.3 (audit trails) | A.12.4.1 (event logging) | CC7.2 (monitoring) |
| Data minimization (Legal R-3) | Art 5 (minimization) | 3.1 (data classification) | A.8.2.1 (classification) | CC5.2 (data handling) |
| JWT rotation (Security S-4) | Art 32 | 8.2 (authentication) | A.9.4.3 (auth) | CC6.1 (logical access) |
| Health checks (O-3) | Art 32 (availability) | 11.5 (vulnerability mgmt) | A.12.3.1 (availability) | A1.2 (availability) |
| Container hardening (SC-16) | Art 32 | 2.2 (secure configs) | A.12.1.2 (vulnerability mgmt) | CC7.1 |
| SSL/TLS (O-2) | Art 32 | 4.1 (encrypt transmission) | A.13.2.1 (data transfer) | CC5.1 |
| Validation (SC-12) | Art 25 (privacy by design) | 6.5 (vulnerabilities) | A.14.2.1 (secure dev) | CC3.1 |
| SBOM/Dependency scanning (S-6) | Art 32 | 6.3 (vulnerabilities) | A.12.6.1 (tech vuln mgmt) | CC7.2 |

---

## Appendix F: Metrics & Alert Catalog

| Prometheus Metric Name | Source | Alert Name | Severity | Description |
|------------------------|--------|------------|----------|-------------|
| `bhukkad_outbox_pending` | OutboxMetrics.java:35 | EventPipelineDisabled | Critical | Events disabled while outbox has pending messages |
| `bhukkad_outbox_pending` | OutboxMetrics.java:35 | OutboxQueueDepth | Warning | Outbox backlog > 1000 |
| `hikaricp_connections_active` | HikariConfig | HikariPoolExhausted | Critical | Pool at max capacity |
| `jvm_threads_live_threads` | JVM | SseConnectionExhaustion | Warning | > 10,000 threads in SSE services |
| `circuit_breaker_open` | CircuitBreakerFilter.java:69 | CircuitBreakerOpen | Warning | Circuit breaker state |
| `pg_stat_ssl_ssl` | PostgreSQL | — | Info | SSL status of DB connections |
| `up{job="postgresql"}` | K8s | DatabasePrimaryUnreachable | Critical | PostgreSQL unreachable |
| `up{job="redis"}` | K8s | RedisMasterDown | Critical | Redis unreachable |

---

*End of Document*
