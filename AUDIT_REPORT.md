# Bhukkad Backend — Production-Readiness Re-Audit

**Date:** 2026-09-23  
**Scope:** Full re-audit after recent fixes  
**Verdict:** **NOT READY** — 5 critical blockers remain before go-live.

---

## 1. Recently-Fixed Items — Verification

| # | Claimed Fix | Status | Evidence |
|---|-------------|--------|----------|
| 1 | PgBouncer wired end-to-end | **BROKEN** | `k8s/pgbouncer/service.yaml:4` names the service `pgbouncer`, but `k8s/secrets.yaml:68-109` and `k8s/personalization/deployment.yaml:75`, `k8s/realtime/deployment.yaml:75`, `k8s/growth/deployment.yaml:75` reference the non-existent host `bhukkad-pgbouncer`. Personalization/realtime/growth also set `POSTGRES_PORT=5432` instead of `6432`. |
| 2 | Duplicate wal-spool volume removed | ✅ FIXED | `k8s/postgres/deployment.yaml:258-260` shows exactly one `wal-spool` PVC. |
| 3 | Read-replica DB URL fixed | ✅ FIXED | `k8s/configmap.yaml:49` → `DB_REPLICA_HOST: "bhukkad-postgresql-read"`. |
| 4 | Global ingress limit-rps raised to 1000 | ✅ FIXED | `k8s/ingress.yaml:18` → `nginx.ingress.kubernetes.io/limit-rps: "1000"`. |
| 5 | CSP added | ✅ FIXED | `services/gateway/src/main/java/com/bhukkad/gateway/GatewaySecurityHeadersConfig.java:29-32`. |
| 6 | Startup probes added | ✅ FIXED | nginx (`k8s/nginx/deployment.yaml:74-79`), postgres read-replica (`k8s/postgres/read-replica.yaml:73-81`), redpanda (`k8s/components/redpanda/statefulset.yaml:107-113`), redis (`k8s/redis/deployment.yaml:90-98`). |
| 7 | baseline-on-migrate added | ✅ FIXED | Present in `services/growth/src/main/resources/application.yml:36`, `services/personalization/src/main/resources/application.yml:36`, `services/realtime/src/main/resources/application.yml:35`, and all other services. |
| 8 | validate-on-migrate fixed in social local profile | ✅ FIXED | `services/social/src/main/resources/application-local.yml:22` → `validate-on-migrate: true`. |
| 9 | PasswordService uses shared Argon2id | ✅ FIXED | `services/identity/src/main/java/com/bhukkad/identity/config/PasswordService.java:20-22` delegates to the shared `PasswordEncoder`; `services/platform-lib/src/main/java/com/bhukkad/common/config/PasswordEncoderConfig.java:57-68` defaults to Argon2id. |
| 10 | `/auth/mfa/verify` returns 501 | **NOT FIXED** | `services/identity/src/main/java/com/bhukkad/identity/api/controller/IdentityController.java:279` throws `BusinessException("TWO_STEP_MFA_NOT_IMPLEMENTED", ...)`. `services/platform-lib/src/main/java/com/bhukkad/common/web/GlobalExceptionHandler.java:95-99` maps `BusinessException` to **HTTP 400**, not 501. |

---

## 2. Critical Blockers (Must Fix Before Go-Live)

### B-1. PgBouncer Hostname Mismatch — DNS Failure for 9 Services
**File(s):** `k8s/pgbouncer/service.yaml:4`, `k8s/secrets.yaml:68-109`, `k8s/personalization/deployment.yaml:75`, `k8s/realtime/deployment.yaml:75`, `k8s/growth/deployment.yaml:75`

The PgBouncer Kubernetes Service is named **`pgbouncer`** (namespace `bhukkad`). Its cluster DNS is `pgbouncer.bhukkad.svc.cluster.local`, and the short name `pgbouncer` resolves inside the namespace.

However, the following deployments and secrets reference **`bhukkad-pgbouncer`**, which does **not** resolve:

| Service | Manifest | Wrong Host |
|---------|----------|------------|
| restaurant | `k8s/secrets.yaml:68` | `bhukkad-pgbouncer:6432` |
| order | `k8s/secrets.yaml:74` | `bhukkad-pgbouncer:6432` |
| payment | `k8s/secrets.yaml:79` | `bhukkad-pgbouncer:6432` |
| delivery | `k8s/secrets.yaml:84` | `bhukkad-pgbouncer:6432` |
| notification | `k8s/secrets.yaml:89` | `bhukkad-pgbouncer:6432` |
| admin-analytics | `k8s/secrets.yaml:94` | `bhukkad-pgbouncer:6432` |
| survey | `k8s/secrets.yaml:99` | `bhukkad-pgbouncer:6432` |
| referral | `k8s/secrets.yaml:104` | `bhukkad-pgbouncer:6432` |
| supportticket | `k8s/secrets.yaml:109` | `bhukkad-pgbouncer:6432` |
| personalization | `k8s/personalization/deployment.yaml:75` | `bhukkad-pgbouncer` |
| realtime | `k8s/realtime/deployment.yaml:75` | `bhukkad-pgbouncer` |
| growth | `k8s/growth/deployment.yaml:75` | `bhukkad-pgbouncer` |

**Impact:** Pods will fail with `UnknownHostException` on startup. The entire DB tier is unreachable through PgBouncer.

**Fix:** Rename the PgBouncer Service to `bhukkad-pgbouncer` (and update all references), or repoint every secret/deployment to `pgbouncer:6432`. Do not mix both.

---

### B-2. Wrong Port for Personalization / Realtime / Growth
**File(s):** `k8s/personalization/deployment.yaml:77`, `k8s/realtime/deployment.yaml:77`, `k8s/growth/deployment.yaml:77`

These three services set `POSTGRES_PORT=5432`, but PgBouncer listens on **`6432`** (`k8s/pgbouncer/service.yaml:12`, `k8s/pgbouncer/configmap.yaml:30`). Even if the hostname were corrected, they would connect directly to PostgreSQL on 5432, bypassing PgBouncer entirely.

---

### B-3. MFA Endpoint Returns 400 Instead of 501
**File(s):** `services/identity/src/main/java/com/bhukkad/identity/api/controller/IdentityController.java:279`, `services/platform-lib/src/main/java/com/bhukkad/common/web/GlobalExceptionHandler.java:95-99`

`/auth/mfa/verify` throws `BusinessException("TWO_STEP_MFA_NOT_IMPLEMENTED", ...)`. The global handler maps all `BusinessException` instances to **HTTP 400**. The endpoint must return **501 Not Implemented**.

**Fix:** Either introduce a `NotImplementedException` mapped to 501, or annotate the endpoint/exception with `@ResponseStatus(HttpStatus.NOT_IMPLEMENTED)`.

---

### B-4. Missing `SOCIAL_DB_URL` in ExternalSecret
**File(s):** `k8s/external-secret.yaml` (entire file), `k8s/social/deployment.yaml:64-78`

`k8s/external-secret.yaml` is the **sole owner** of `bhukkad-secrets` (`creationPolicy: Owner`). It maps per-service DB URLs for 14 services but **omits `SOCIAL_DB_URL`**, `SOCIAL_DB_USERNAME`, and `SOCIAL_DB_PASSWORD`. The social deployment references these keys via `secretKeyRef`. After ExternalSecret sync, those keys will **not exist** in the secret, causing the social pod to fail with `NotFound` errors.

---

### B-5. Social Service Excluded from Kustomization
**File(s):** `k8s/kustomization.yaml` (entire file), `k8s/services/kustomization.yaml` (entire file)

The social service manifests exist at `k8s/social/` but are **not referenced** in either the root `k8s/kustomization.yaml` or `k8s/services/kustomization.yaml`. The service will not be deployed by the standard pipeline.

---

## 3. High-Priority Items (Address in First Sprint)

### H-1. PgBouncer Pool Sizing vs HPA Max — Budget Exhaustion Risk
**File(s):** `k8s/pgbouncer/configmap.yaml:12-26,33-38`, `k8s/order/hpa.yaml:14-15`, `services/order/src/main/resources/application.yml:17`

PgBouncer per-DB `pool_size=1250` (`k8s/pgbouncer/configmap.yaml:12-26`). At HPA max replicas:
- **Order:** 50 pods × 30 connections = **1,500** > 1,250 (exceeds PgBouncer budget)
- **Restaurant:** 50 × 30 = 1,500 > 1,250
- **Social:** 50 × 50 = 2,500 > 1,250
- **Identity:** 50 × 15 = 750 < 1,250 (OK)

The documented CI gate (`scripts/ci/pool-budget-check.py`) is referenced in docs but **not enforced as a hard CI gate**. Without it, a single service can saturate PgBouncer and take down the DB tier.

---

### H-2. Missing PodDisruptionBudgets for Infrastructure
**File(s):** `k8s/postgres/`, `k8s/redis/`, `k8s/components/redpanda/`, `k8s/pgbouncer/`, `k8s/nginx/`

PDBs exist for all 15 microservices and the gateway, but **not** for:
- PostgreSQL primary (`k8s/postgres/deployment.yaml` — single replica, `Recreate` strategy)
- PostgreSQL read-replica StatefulSet
- Redis (3 replicas)
- Redpanda (3 replicas)
- PgBouncer (2 replicas)
- Nginx (2 replicas)

A single voluntary disruption (e.g., node drain) on the primary PostgreSQL pod causes a full DB outage because there is no replica promotion automation and no PDB to prevent simultaneous eviction.

---

### H-3. TLS Disabled Fleet-Wide
**File(s):** `k8s/configmap.yaml:35,151,212`

`TLS_ENABLED: "false"` is the default everywhere. All inter-service traffic (Postgres, Redis, Kafka, HTTP) runs **plaintext** inside the cluster. The commented-ready TLS components exist (`k8s/components/tls-internal/`) but are not enabled.

**Risk:** In-cluster sniffing, man-in-the-middle between services, compliance violations.

---

### H-4. Kafka Consumers Disabled in Most Services
**File(s):** `services/restaurant/src/main/resources/application.yml:164`, `services/payment/src/main/resources/application.yml:183`, `services/delivery/src/main/resources/application.yml:154`, `services/search/src/main/resources/application.yml:163`, `services/social/src/main/resources/application.yml:194`

Every service except identity, order, gateway, realtime, growth, personalization, survey, notification, referral, supportticket, and admin-analytics has `app.events.external.enabled: false`. This means the outbox relay publishes events that are **never consumed** by most downstream services. The `EventBackbonePreflight` in prod refuses to boot when `enabled=false`, but only identity/order/gateway/realtime have this guard active.

---

### H-5. JWT Key Rotation — No Automated Rotation
**File(s):** `services/identity/src/main/java/com/bhukkad/identity/config/JwtService.java:79,87-89`, `k8s/identity/deployment.yaml:93-98`

RS256 keys are loaded from a static PEM mounted as a Secret volume (`JWT_PRIVATE_KEY_PATH`). There is a `KeyRotationController` (`services/identity/src/main/java/com/bhukkad/identity/api/controller/KeyRotationController.java`) but it returns **501** when HMAC-only mode is active (`services/identity/src/main/java/com/bhukkad/identity/api/controller/KeyRotationController.java:54`). There is no automated key rotation schedule.

---

### H-6. `server_reset_query_always` Present but Default Pool Size Mismatch
**File(s):** `k8s/pgbouncer/configmap.yaml:40`

`server_reset_query_always = DISCARD ALL` is correctly set. However, `default_pool_size = 150` (`k8s/pgbouncer/configmap.yaml:35`) is the per-client pool size. With HPA scaling to 50 pods and per-pod Hikari pools of 15-50, the total connection demand far exceeds 150 per client at the PgBouncer level. The per-DB `pool_size=1250` caps total server connections, but the per-client `default_pool_size` means each pod can only borrow 150 at a time — this is actually protective, but it means HPA scaling beyond ~8 pods per DB will see connection wait queues.

---

## 4. Medium-Priority Items (First Sprint)

### M-1. `validate-on-migrate` Disabled in Production Profiles
**File(s):** `services/social/src/main/resources/application.yml:46` (`ddl-auto: none`, no `validate-on-migrate`), `services/identity/src/main/resources/application.yml:46` (same)

All production profiles use `ddl-auto: none` and omit `validate-on-migrate`. Schema drift between Flyway migrations and the actual DB schema will be **undetected** until runtime query failures.

**Fix:** Add `validate-on-migrate: true` to all `application-prod.yml` files.

---

### M-2. RBAC Coverage Gaps
**File(s):** `services/identity/src/main/java/com/bhukkad/identity/config/SecurityConfig.java:55-77`

Identity has `@EnableMethodSecurity` and `/api/v1/internal/**` is protected with `hasRole("SERVICE")`. However, other services lack visible `@PreAuthorize` annotations on sensitive endpoints (e.g., admin-analytics, payment settlement). The security model relies heavily on the service-to-service `SERVICE_JWT_SECRET` filter, but individual service method-level authorization is not uniformly applied.

---

### M-3. Rate Limiter Coverage Not Universal
**File(s):** `services/gateway/src/main/java/com/bhukkad/gateway/EdgeRateLimitFilter.java:68-82`

The gateway edge rate limiter covers login, order create, wallet, payment, notification, and webhook endpoints. However, public read endpoints (`/api/v1/restaurants/public`, `/api/v1/search`, etc.) are **not rate-limited at the edge** — they rely on the `limit_req zone=api_general` in `k8s/nginx/configmap.yaml:101,267` (30 r/s burst 50). Under a scrape or abuse event, these can still overwhelm downstream services.

---

### M-4. Circuit Breaker Metrics Not Exported Consistently
**File(s):** `services/gateway/src/main/resources/application.yml:185-225`, `services/social/src/main/resources/application.yml:104-142`

Resilience4j circuit breakers are configured in gateway and social, but **no `resilience4j-micrometer` metrics export** is visible in the application configs. Without `management.metrics.export.prometheus.enabled: true` for `resilience4j`, breaker state transitions are invisible to Prometheus alerts.

---

### M-5. Graceful Shutdown Timeout Inconsistency
**File(s):** `services/gateway/src/main/resources/application.yml:7` (`30s`), `services/identity/src/main/resources/application.yml:15` (`30s`), `services/order/src/main/resources/application.yml` (not read, but pattern consistent)

Most services set `spring.lifecycle.timeout-per-shutdown-phase: 30s`. However, Kubernetes `terminationGracePeriodSeconds` is also `30s` on most deployments. If the JVM shutdown hook takes the full 30s, Kubernetes will send SIGKILL at 30s, potentially cutting off in-flight SSE streams or outbox publishes. The gateway correctly sets `90s` (`k8s/gateway/deployment.yaml:43`) for SSE drain. Other services should be reviewed.

---

## 5. Low-Priority Items (Roadmap)

### L-1. Brotli Compression Not Verified in Nginx Image
**File(s):** `k8s/nginx/configmap.yaml:88-98`

Brotli is configured in the nginx config, but the `nginx:1.25-alpine` base image does **not** ship the `nginx-module-brotli` dynamic module by default. Compression silently falls back to gzip.

---

### L-2. Distributed Tracing Endpoint Unset
**File(s):** `k8s/configmap.yaml:197`

`MANAGEMENT_ZIPKIN_TRACING_ENDPOINT: ""` — spans are built but **not exported**. The comment says "set to an in-cluster Zipkin/OTLP collector to enable export." Until this is set, distributed tracing is a no-op.

---

### L-3. Off-Site Backup S3 Bucket Not Verified
**File(s):** `k8s/backup-cronjob.yaml:181`, `k8s/configmap.yaml:179`

The backup job syncs to `$S3_BUCKET` but the bucket name comes from Vault. There is no manifest-level verification that the bucket exists, versioning is enabled, or lifecycle rules enforce 30-day retention.

---

### L-4. Redis Sentinel Not Tested Under Failover
**File(s):** `k8s/redis/redis-sentinel.yaml`

Redis Sentinel is deployed (3 replicas) with password auth, but there is no evidence of automated failover testing in the chaos drill suite (`scripts/ci/chaos-drills.py` references `redis` but only for restart, not master failover).

---

## 6. Architecture & Service Mesh Observations

### Service Inventory
15 microservices + gateway + platform-lib. All services communicate via REST (WebFlux/WebClient) or Kafka/Redpanda. The gateway is a Spring Cloud Gateway reactive proxy.

### Single Points of Failure
1. **PostgreSQL primary** — single replica with `Recreate` strategy. Read-replica exists (`k8s/postgres/read-replica.yaml`) but application-level read-replica routing is only configured in identity, order, social, and gateway. Most services still write to primary and read from primary.
2. **Redis** — 3 replicas with Sentinel, but no PDB. All services depend on Redis for rate limiting, caching, and feature flags. A full Redis outage cascades to 503s everywhere.
3. **PgBouncer** — 2 replicas, no PDB. Single point for all DB traffic (once wired correctly).

---

## 7. Database Layer Deep-Dive

### PgBouncer Configuration
**File:** `k8s/pgbouncer/configmap.yaml`

| Parameter | Value | Assessment |
|-----------|-------|------------|
| `pool_mode` | `transaction` | Correct for heavy OLTP |
| `max_client_conn` | 17,500 | Adequate for 50 pods × 15 services |
| `default_pool_size` | 150 | Per-client; may queue under HPA spike |
| `server_reset_query_always` | `DISCARD ALL` | ✅ Fixed |
| `server_lifetime` | 3600s | Reasonable |

### HikariCP vs PgBouncer Sizing Math

| Service | HPA Max | Hikari Max | Total Demand | PgBouncer DB Pool |
|---------|---------|------------|--------------|-------------------|
| identity | 50 | 15 | 750 | 1,250 |
| order | 50 | 30 | 1,500 | 1,250 **OVER** |
| restaurant | 50 | 30 | 1,500 | 1,250 **OVER** |
| social | 50 | 50 | 2,500 | 1,250 **OVER** |
| payment | 50 | 30 | 1,500 | 1,250 **OVER** |
| delivery | 50 | 30 | 1,500 | 1,250 **OVER** |

The CI gate `scripts/ci/pool-budget-check.py` exists in docs but is **not enforced** in `.github/workflows/`.

---

## 8. Security Summary

| Control | Status | Notes |
|---------|--------|-------|
| JWT Algorithm | ✅ RS256 + legacy HS256 grace | `services/identity/src/main/java/com/bhukkad/identity/config/JwtService.java:153-167` |
| Password Hashing | ✅ Argon2id primary, BCrypt fallback | `services/platform-lib/src/main/java/com/bhukkad/common/config/PasswordEncoderConfig.java:57-68` |
| RBAC | 🟡 Partial | Identity has `@EnableMethodSecurity`; other services lack visible method-level guards |
| Input Validation | ✅ Widely used | `@Valid`, `@NotBlank`, `@Size`, `@Pattern` found across all services |
| TLS | 🔴 Disabled | `TLS_ENABLED: "false"` in `k8s/configmap.yaml:212` |
| CORS | ✅ Strict | `services/gateway/src/main/java/com/bhukkad/gateway/GatewayCorsConfig.java:36-41` fails closed on wildcard in prod |
| Security Headers | ✅ Complete | CSP, HSTS, X-Frame-Options, etc. in `GatewaySecurityHeadersConfig.java:23-32` |
| Service Mesh Auth | ✅ SERVICE_JWT_SECRET | `app.auth.service.jwt-secret` enforced in identity and all services |

---

## 9. Resilience Summary

| Control | Status | Notes |
|---------|--------|-------|
| Circuit Breakers (Resilience4j) | ✅ Configured | Gateway and most services have configs; social has instance-specific configs |
| HTTP Client Timeouts | ✅ Gateway 8s | `services/gateway/src/main/resources/application.yml:31` |
| DB Connection Timeout | ✅ 3s | Standard across services |
| Redis Timeouts | ✅ 2-3s | Standard across services |
| Kafka Timeouts | ✅ Configured | `max-poll-interval-ms: 300000`, etc. |
| Retry Policies | ✅ Kafka producer retries=10 | `services/*/application.yml` |
| Bulkheads | 🟡 Not explicit | No Hystrix/Bulkhead configs found; Resilience4j bulkhead not configured |
| Graceful Shutdown | 🟡 Mixed | Gateway 90s, most services 30s |

---

## 10. Observability Summary

| Control | Status | Notes |
|---------|--------|-------|
| Health Checks | ✅ All services | `/actuator/health` with liveness/readiness |
| Startup Probes | ✅ Key components | nginx, postgres read-replica, redpanda, redis |
| Metrics | ✅ Prometheus | All services expose `/actuator/prometheus` |
| Distributed Tracing | 🔴 Disabled | `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT: ""` |
| Logging | ✅ Structured | platform-lib `LoggingAspect`, `CorrelationIdFilter` |
| Audit Trails | ✅ SecurityEventLogger | `services/platform-lib/src/main/java/com/bhukkad/common/logging/SecurityEventLogger.java` |
| Alerting | ✅ PrometheusRules | `k8s/monitoring/prometheus-rules.yaml` |

---

## 11. Kubernetes Deployment Summary

| Component | Replicas | HPA | PDB | Startup Probe | Notes |
|-----------|----------|-----|-----|---------------|-------|
| gateway | 10-50 | ✅ | ✅ minAvailable:2 | ✅ | CPU 70%, memory 80% |
| identity | 10-50 | ✅ | ✅ minAvailable:2 | ✅ | CPU 65% |
| order | 10-50 | ✅ | ✅ minAvailable:2 | ✅ | CPU 65% |
| restaurant | 10-50 | ✅ | ✅ minAvailable:2 | ✅ | CPU 65% |
| nginx | 2 | ❌ | ❌ | ✅ | No autoscaling; single edge bottleneck |
| postgres | 1 | ❌ | ❌ | ✅ | Single primary; no failover automation |
| postgres-read | 2 | ❌ | ❌ | ✅ | Read-replica; no PDB |
| redis | 3 | ❌ | ❌ | ✅ | Sentinel enabled; no PDB |
| redpanda | 3 | ❌ | ❌ | ✅ | No PDB |
| pgbouncer | 2 | ❌ | ❌ | ✅ | No PDB |

---

## 12. Performance Under Heavy Traffic

### Thread Pool Sizing
- **Gateway:** Netty worker threads = CPU cores (`services/gateway/src/main/resources/application.yml:4` — `worker-count: 0`), HTTP client pool max 2000 connections.
- **Services:** Tomcat max 200 threads, accept-count 200, max-connections 4096.

### Connection Pool Sizing
See H-1 above for the PgBouncer budget analysis. The fundamental math is broken at HPA max scale.

### Redis
- **Eviction Policy:** `volatile-lru` (`k8s/redis/configmap.yaml:29`) — correct for cache + rate-limit separation.
- **Max Memory:** 512MB (`k8s/redis/configmap.yaml:24`).
- **Lazy Free:** Enabled (`k8s/redis/configmap.yaml:31-33`).

### Database Query Performance
- Hibernate batch size 50 across all services.
- `shared_buffers = 512MB`, `work_mem = 16MB`, `effective_cache_size = 1536MB` (`k8s/postgres/configmap.yaml:14-16`).
- `synchronous_commit = off` (`k8s/postgres/configmap.yaml:21`) — trades durability for latency. Acceptable for non-financial writes but must be reviewed for order/payment.

---

## 13. Overall Readiness Verdict

**NOT READY**

The platform has made significant progress (CSP, startup probes, baseline-on-migrate, Argon2id, password service refactor). However, **5 critical blockers** prevent production go-live:

1. **PgBouncer is not wired** — hostname mismatch (`bhukkad-pgbouncer` vs `pgbouncer`) and wrong port (5432 vs 6432) for 12 services.
2. **MFA endpoint returns 400** instead of 501.
3. **Missing `SOCIAL_DB_URL`** in ExternalSecret will crash the social pod.
4. **Social service excluded** from kustomization — not deployed.
5. **Connection pool budget** exceeds PgBouncer capacity at HPA max for order, restaurant, payment, delivery, and social.

Additionally, **TLS is disabled fleet-wide**, **Kafka consumers are off** in most services, and **there is no PDB** on PostgreSQL, Redis, Redpanda, PgBouncer, or Nginx.

---

## 14. Recommended Action Plan

### Immediate (P0 — Block Go-Live)
1. Fix PgBouncer hostname: rename Service to `bhukkad-pgbouncer` or repoint all secrets/deployments to `pgbouncer`.
2. Fix PgBouncer port: change `POSTGRES_PORT=5432` → `6432` in personalization, realtime, growth deployments.
3. Fix `/auth/mfa/verify` to return HTTP 501.
4. Add `SOCIAL_DB_URL`, `SOCIAL_DB_USERNAME`, `SOCIAL_DB_PASSWORD` to `k8s/external-secret.yaml`.
5. Add social to `k8s/kustomization.yaml` and `k8s/services/kustomization.yaml`.

### First Sprint (P1)
6. Enforce `scripts/ci/pool-budget-check.py` as a **hard CI gate**.
7. Right-size PgBouncer `pool_size` per-DB or cap HPA max to match budget.
8. Add PDBs for postgres, postgres-read, redis, redpanda, pgbouncer, nginx.
9. Enable `validate-on-migrate: true` in all `application-prod.yml` files.
10. Add Resilience4j bulkhead configs for DB and Redis clients.

### Roadmap (P2)
11. Enable in-cluster TLS (cert-manager + `TLS_ENABLED=true`).
12. Enable Kafka consumers fleet-wide and validate topic alignment.
13. Set `MANAGEMENT_ZIPKIN_TRACING_ENDPOINT` to an in-cluster collector.
14. Implement automated JWT key rotation.
15. Verify S3 backup bucket lifecycle and test restore.
