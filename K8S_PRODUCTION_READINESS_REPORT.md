# Bhukkad Backend — Kubernetes Production-Readiness Audit Report

**Date:** 2026-09-23  
**Scope:** k8s/ manifests, ingress, database, redis, kafka, autoscaling, probes, chaos/load testing  
**Status:** Read-only analysis — no files modified

---

## 1. k8s/ Directory Overview

| Component | Kind | Replicas | Key File |
|-----------|------|----------|----------|
| Gateway | Deployment | HPA 10–50 | `k8s/gateway/deployment.yaml` |
| Order | Deployment | HPA 10–50 | `k8s/order/deployment.yaml` |
| Restaurant | Deployment | HPA 10–50 | `k8s/restaurant/deployment.yaml` |
| Payment | Deployment | HPA 10–50 | `k8s/payment/deployment.yaml` |
| Delivery | Deployment | HPA 10–50 | `k8s/delivery/deployment.yaml` |
| Identity | Deployment | HPA 10–50 | `k8s/identity/deployment.yaml` |
| Search | Deployment | HPA 10–50 | `k8s/search/deployment.yaml` |
| Notification | Deployment | HPA 10–50 | `k8s/notification/deployment.yaml` |
| Realtime | Deployment | HPA 10–50 | `k8s/realtime/deployment.yaml` |
| Personalization | Deployment | HPA 10–50 | `k8s/personalization/deployment.yaml` |
| Growth | Deployment | HPA 10–50 | `k8s/growth/deployment.yaml` |
| Survey | Deployment | HPA 10–50 | `k8s/survey/deployment.yaml` |
| Referral | Deployment | HPA 10–50 | `k8s/referral/deployment.yaml` |
| Supportticket | Deployment | HPA 10–50 | `k8s/supportticket/deployment.yaml` |
| Social | Deployment | HPA 10–50 | `k8s/social/deployment.yaml` |
| Admin-analytics | Deployment | HPA 10–50 | `k8s/admin-analytics/deployment.yaml` |
| Nginx (edge) | Deployment | 2 | `k8s/nginx/deployment.yaml` |
| Postgres (primary) | **Deployment** | 1 | `k8s/postgres/deployment.yaml` |
| Postgres (read-replica) | StatefulSet | 2 | `k8s/postgres/read-replica.yaml` |
| Redis | Deployment | 3 | `k8s/redis/deployment.yaml` |
| Redis Sentinel | Deployment | 3 | `k8s/redis/redis-sentinel.yaml` |
| Redis Cluster | StatefulSet | 6 | `k8s/redis/cluster/statefulset.yaml` |
| Redpanda | StatefulSet | 3 | `k8s/components/redpanda/statefulset.yaml` |
| PgBouncer | Deployment | 2 | `k8s/pgbouncer/deployment.yaml` |

**Topology spread:** All deployments include `topologySpreadConstraints` for zone and hostname (`maxSkew: 1`, `ScheduleAnyway`).  
**Priority class:** All Java services use `production-high` (value 1,000,000). `k8s/common/priority-class.yaml`  
**Tolerations/affinity:** No `tolerations`, `nodeAffinity`, or `podAffinity` found in any manifest.

---

## 2. ConfigMaps & Secrets

### External-Secret + Vault Integration
- **SecretStore:** `k8s/secret-store.yaml` — Vault provider at `https://vault.bhukkad.internal:8200`, Kubernetes auth, refresh interval 1h.
- **ExternalSecret:** `k8s/external-secret.yaml` — Single owner of `bhukkad-secrets` (`creationPolicy: Owner`). Maps 60+ keys from Vault paths `bhukkad/prod/*` (database, auth, redis, redpanda, backup, payments).
- **Static secrets.yaml:** Deliberately excluded from base kustomization to avoid the race condition where a static Secret and ExternalSecret fight over the same name (`k8s/kustomization.yaml:10-20`).

**Finding — PgBouncer credentials missing from ExternalSecret:**  
PgBouncer expects `DB_USER`/`DB_PASSWORD` env vars (`k8s/pgbouncer/deployment.yaml:51-59`), but no mapping for `DB_USERNAME` exists in `k8s/external-secret.yaml`. The deployment references `bhukkad-secrets/DB_USERNAME`, which is present, so this is safe — but the PgBouncer `userlist.txt` generation relies on the edoburu image entrypoint reading those exact env vars.

### Main ConfigMap
- **File:** `k8s/configmap.yaml` (217 lines)
- **Key values:**
  - `DB_SSL_ENABLED: "false"` (TLS off by default)
  - `APP_EVENTS_EXTERNAL_ENABLED: "true"` — fleet-wide Kafka enabled
  - `KAFKA_BOOTSTRAP_SERVERS` — headless Service DNS for 3 brokers
  - `TLS_ENABLED: "false"` — in-cluster TLS off by default
  - `TRACING_SAMPLE_PROBABILITY: "0.1"` — 10% sampling

---

## 3. Ingress

**File:** `k8s/ingress.yaml`

| Property | Value | Line |
|----------|-------|------|
| Class | `nginx` | 20 |
| TLS host | `api.bhukkad.com` | 22-24 |
| SSL redirect | `true` + `force-ssl-redirect` | 10-11 |
| Proxy body size | `10m` | 12 |
| Proxy connect timeout | `30s` | 13 |
| Proxy read/send timeout | `3600s` (SSE-safe) | 14-15 |
| Proxy buffering | `off` | 16 |
| Upstream keepalive | `64` | 17 |
| **Global rate limit** | **`limit-rps: 30`** | **18** |

**CRITICAL — Global ingress rate limit caps every client IP at 30 RPS:**  
`k8s/ingress.yaml:18` — `nginx.ingress.kubernetes.io/limit-rps: "30"`. This is a global default applied before any per-path nginx limits. Under heavy traffic or from CGNAT IPs, this silently throttles all clients. The per-path zones in `k8s/nginx/configmap.yaml:101-108` (e.g., `api_general: 30r/s`) are redundant with this global cap.

**Secondary edge (nginx Deployment):**  
- `k8s/nginx/deployment.yaml` — 2 replicas, `worker_connections 1024`, upstream `keepalive 32`.
- `k8s/nginx/configmap.yaml` — per-path rate limits, security headers, SSE proxy settings, swagger blocked in prod.
- `k8s/nginx/service.yaml` — LoadBalancer, port 80 → 80.

---

## 4. Database — PostgreSQL

### Primary
- **File:** `k8s/postgres/deployment.yaml`
- **Kind:** Deployment (not StatefulSet) — **single point of failure for primary**
- **Replicas:** 1
- **Resources:** CPU 300m–1, memory 768Mi–1536Mi
- **Storage:** `bhukkad-postgres-pvc` — 10Gi, `standard` SC (`k8s/postgres/pvc.yaml`)
- **Probes:** startup (pg_isready, 5s/30 failures), liveness (10s/3 failures), readiness (5s/3 failures)
- **PITR:** wal-g sidecar with sha256-verified binary install, WAL spool PVC (5Gi), nightly base backup + S3 upload.
- **BUG — Duplicate volume key:** `wal-spool` is declared twice in the `volumes` list (`k8s/postgres/deployment.yaml:258-260` as PVC, `k8s/postgres/deployment.yaml:279-281` as emptyDir). Kubernetes will reject this manifest with a duplicate key error.

### Read Replica
- **File:** `k8s/postgres/read-replica.yaml`
- **Kind:** StatefulSet
- **Replicas:** 2
- **Service:** `bhukkad-postgresql-read` (ClusterIP, port 5432)
- **Resources:** CPU 300m–1500m, memory 1Gi–2Gi
- **Probes:** readiness (5s), liveness (10s/5 failures) — **no startupProbe**

### Postgres Config
- **File:** `k8s/postgres/configmap.yaml`
- **max_connections:** 500
- **shared_buffers:** 512MB, `effective_cache_size`: 1536MB
- `synchronous_commit: off` — **potential data loss on primary crash**
- `archive_mode: on`, `archive_command` spools to `/wal-spool`
- TLS placeholder via `include_if_exists`

### PgBouncer
- **File:** `k8s/pgbouncer/deployment.yaml` + `k8s/pgbouncer/configmap.yaml`
- **Deployed:** 2 replicas, port 6432
- **CRITICAL — Deployed but UNUSED:** All service deployments reference `DB_URL` pointing to `bhukkad-postgresql:5432` (direct), not `bhukkad-pgbouncer:6432`. PgBouncer is in the kustomization base but no consumer is wired to it.
- **Missing:** `server_reset_query_always = DISCARD ALL` — required to prevent session `search_path` leaks (documented in audit docs).
- **Pool config:** `default_pool_size = 150`, `max_client_conn = 17500`, per-DB pool sizes 1250 — exceeds `max_connections=500` on Postgres.
- **No HPA** on PgBouncer.

---

## 5. Redis

### Standalone Deployment (Production)
- **File:** `k8s/redis/deployment.yaml`
- **Replicas:** 3
- **Resources:** CPU 100m–500m, memory 256Mi–512Mi
- **Persistence:** `bhukkad-redis-pvc` — 2Gi (`k8s/redis/pvc.yaml`)
- **Probes:** startup (auth ping, 5s/30 failures), liveness (10s), readiness (5s)
- **Password:** `requirepass` injected via command line from `bhukkad-secrets/REDIS_PASSWORD`

### Redis Config
- **File:** `k8s/redis/configmap.yaml`
- `maxmemory 512mb`, `maxmemory-policy volatile-lru`
- AOF enabled (`appendonly yes`, `appendfsync everysec`)
- TLS placeholder (off by default)

### Redis Sentinel
- **File:** `k8s/redis/redis-sentinel.yaml`
- **Replicas:** 3
- Monitors `bhukkad-master` at `bhukkad-redis:6379`
- `sentinel auth-pass` uses `${REDIS_PASSWORD}`

### Redis Cluster (Secondary/Phase 2)
- **File:** `k8s/redis/cluster/statefulset.yaml`
- **Replicas:** 6
- **Resources:** CPU 500m, memory 1Gi–2Gi
- **Storage:** 20Gi per pod
- **Config:** `cluster-enabled yes`, `maxmemory 1.5gb`, `maxmemory-policy allkeys-lru`
- **No readiness/liveness probes** defined in the cluster StatefulSet.

**Finding — Triple Redis topology:** The repo ships three Redis configurations (standalone 3-replica Deployment, 3-replica Sentinel, 6-replica Cluster StatefulSet). Only the standalone + sentinel are wired into the base kustomization. The cluster is dormant but present, creating confusion about the production topology.

---

## 6. Kafka — Redpanda

- **File:** `k8s/components/redpanda/statefulset.yaml`
- **Kind:** StatefulSet
- **Replicas:** 3
- **Resources:** CPU 500m, memory 1Gi (requests == limits — Guaranteed QoS)
- **Storage:** 20Gi PVC per broker
- **Image:** `redpandadata/redpanda:v23.3.13`
- **Listeners:** PLAINTEXT `0.0.0.0:9092`, advertise via headless DNS
- **Probes:** readiness (`/v1/status/ready` on admin port 9644), liveness (TCP on 9092) — **no startupProbe**
- **TLS/SASL:** Placeholder only — off by default. Flip requires node-config changes (not CLI flags).

### Topic Seeding
- **File:** `k8s/components/redpanda/topics-job.yaml`
- Seeds 5 topics + 5 DLT topics with 12–24 partitions each, replication factor 3.
- Topics: `order.events.v1`, `bhukkad.platform.events`, `payment.events.v1`, `restaurant.events.v1`, `notification.events.v1`.

### Kafka Config Gaps
- **No `min.insync.replicas` or `acks` configuration** in k8s manifests — broker-level defaults apply.
- **No Kafka consumer lag HPA** — only Prometheus rules (`k8s/monitoring/kafka-lag-rules.yaml`) and exporters.

---

## 7. Service Mesh / API Gateway

### Istio
- **Docs exist:** `docs/istio-install.md` references `services/k8s/mtls-istio.yaml` and `services/k8s/network-policy.yaml`
- **Manifests MISSING:** No `mtls-istio.yaml` or `network-policy.yaml` exist anywhere in the repo. Istio is documented but **not deployed**.

### Nginx (Edge API Gateway)
- **File:** `k8s/nginx/deployment.yaml` + `k8s/nginx/configmap.yaml`
- **Replicas:** 2
- **Rate limiting:** Per-zone `limit_req_zone` with `limit_req` + `limit_conn` per IP
- **Security headers:** HSTS, X-Frame-Options, X-Content-Type-Options, Permissions-Policy
- **SSE support:** `proxy_buffering off`, `chunked_transfer_encoding on`, 360s read/send timeout
- **Swagger blocked:** Returns 404 in production

---

## 8. Autoscaling

### HPA (Horizontal Pod Autoscaler)
All 15+ services have HPAs in `k8s/*/hpa.yaml`:

| Service | Min | Max | CPU Target | Custom Metrics |
|---------|-----|-----|------------|----------------|
| Gateway | 10 | 50 | 70% | HTTP req rate, reactor netty pending tasks |
| Order | 10 | 50 | 65% | HTTP req rate, Hikari pending connections |
| Restaurant | 10 | 50 | 65% | HTTP req rate (overlay available) |
| Social | 10 | 50 | 65% | HTTP req rate, Hikari pending |
| Realtime | 10 | 50 | 65% | HTTP req rate |
| All others | 10 | 50 | 65% | HTTP req rate |

**Scale-down stabilization:** 300s (5 min) on all services.  
**Scale-up policies:** 100% percent or +2 pods per 30s, `selectPolicy: Max`.

**Custom-metrics overlay:** `k8s/overlays/custom-metrics-hpa/hpa-custom-metrics.yaml` retargets order HPA to min 3 / max 20 with CPU 70% and HTTP req rate 30 RPS/pod. Requires Prometheus Adapter.

### VPA / KEDA
- **No VerticalPodAutoscaler** manifests found.
- **No KEDA** ScaledObject/ScaledJob/TriggerAuthentication found.

---

## 9. Readiness / Liveness / Startup Probes

### Pattern (Java services)
All Java services follow a consistent probe pattern:

```yaml
startupProbe:
  httpGet:
    path: /actuator/health        # or /actuator/health/readiness
    port: http
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 60           # ~10 min startup window
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  initialDelaySeconds: 90
  periodSeconds: 10
  timeoutSeconds: 2
  failureThreshold: 5
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  initialDelaySeconds: 15
  periodSeconds: 5
  timeoutSeconds: 2
  failureThreshold: 3
```

**Exceptions:**
- Postgres: exec `pg_isready` with startup/liveness/readiness (`k8s/postgres/deployment.yaml:132-158`)
- Redis: exec `redis-cli ping` with auth (`k8s/redis/deployment.yaml:90-114`)
- PgBouncer: TCP socket on 6432, no startupProbe (`k8s/pgbouncer/deployment.yaml:77-86`)
- Nginx: HTTP GET `/` on port 80, no startupProbe (`k8s/nginx/deployment.yaml:60-73`)
- Redpanda Cluster: **no probes defined** (`k8s/redis/cluster/statefulset.yaml`)

---

## 10. Chaos Testing & Load Testing

### Chaos Drills
- **Script:** `scripts/ci/chaos-drills.py` — 6 drills: Redis kill, Kafka kill, Gateway kill, PgBouncer restart, 503 storm, shard interleave.
- **CI Workflow:** `.github/workflows/chaos-drill.yml` — manual dispatch, guarded by `STAGING_KUBECONFIG` secret.
- **Additional:** `scripts/ci/chaos-test.py` (smaller subset), `scripts/ci/run-chaos-drills.sh`.

### Load Tests
- **Directory:** `scripts/loadtest/` (k6 scripts)
- **Scenarios:** 10 TPS smoke, 100 TPS, 500 TPS, 3× peak (6000 TPS), 5× peak (10000 TPS), order-create, restaurant-feed, SSE.
- **Success criteria:** p95 < 500ms, error rate < 1% (`scripts/loadtest/README.md:81-88`).
- **Root directory also has:** `loadtest/` with duplicate k6 scenarios.

### CI Integration
- `.github/workflows/services.yml` enforces pool arithmetic (`scripts/ci/pool-budget-check.py`).
- Chaos drills are **manual-only** (not scheduled); weekly cron is commented out (`.github/workflows/chaos-drill.yml:34-35`).

---

## 11. Critical Findings (Prioritized)

### CRITICAL

| # | Finding | File:Line |
|---|---------|-----------|
| C1 | **PgBouncer deployed but UNUSED.** All services connect directly to `bhukkad-postgresql:5432`. The pooler exists in kustomization but is dead code. | `k8s/pgbouncer/`, `k8s/order/deployment.yaml:69-83` (and all other service deployments) |
| C2 | **Postgres primary is a Deployment (1 replica), not StatefulSet.** Single point of failure; no stable network ID for failover. | `k8s/postgres/deployment.yaml:2,10` |
| C3 | **Duplicate `wal-spool` volume key** in Postgres Deployment — K8s will reject the manifest. | `k8s/postgres/deployment.yaml:258-260` and `k8s/postgres/deployment.yaml:279-281` |
| C4 | **Global ingress `limit-rps: 30`** caps every client IP at 30 RPS before per-path limits apply. Will throttle heavy traffic. | `k8s/ingress.yaml:18` |
| C5 | **Db URL mismatch for read replica.** ConfigMap advertises `bhukkad-postgresql-replica:5432` but the actual Service is `bhukkad-postgresql-read`. | `k8s/configmap.yaml:49` vs `k8s/postgres/read-replica.yaml:84` |

### HIGH

| # | Finding | File:Line |
|---|---------|-----------|
| H1 | **Redis triple topology confusion.** Standalone (3), Sentinel (3), and Cluster (6) StatefulSets coexist. Only standalone+sentinel are active. | `k8s/redis/deployment.yaml`, `k8s/redis/redis-sentinel.yaml`, `k8s/redis/cluster/statefulset.yaml` |
| H2 | **PgBouncer `server_reset_query` missing.** Required for session cleanup; without it, session state (e.g., `search_path`) leaks across pooled connections. | `k8s/pgbouncer/configmap.yaml:33` |
| H3 | **Postgres `max_connections=500`** may be exceeded at HPA max: ~14 services × HPA max 50 × Hikari pool 20 = 14,000 potential connections, even accounting for pgbouncer being unused. | `k8s/postgres/configmap.yaml:18` |
| H4 | **Redpanda cluster has no startupProbe.** Brokers may be marked ready before WAL/segment close is complete. | `k8s/components/redpanda/statefulset.yaml:95-106` |
| H5 | **Redis Cluster StatefulSet has no probes.** Pods can be routed to before Redis is ready to serve. | `k8s/redis/cluster/statefulset.yaml` |
| H6 | **Istio mTLS documented but not implemented.** `docs/istio-install.md` references manifests at `services/k8s/` that do not exist. | `docs/istio-install.md:7-9` |

### MEDIUM

| # | Finding | File:Line |
|---|---------|-----------|
| M1 | **No VPA or KEDA.** Only HPA is present. Memory-based autoscaling for JVM heap is unreliable (noted in comments). | Entire `k8s/` tree |
| M2 | **No node affinity, tolerations, or topology spread `DoNotSchedule`.** All spread constraints use `ScheduleAnyway`, which does not prevent co-location during zone outages. | All deployment specs |
| M3 | **Nginx has no startupProbe.** During cold start or config reload, the pod may receive traffic before nginx is ready. | `k8s/nginx/deployment.yaml:60-73` |
| M4 | **Chaos drills are manual-only.** The weekly scheduled cron is commented out, so drills only run on manual dispatch. | `.github/workflows/chaos-drill.yml:34-35` |
| M5 | **Gateway `terminationGracePeriodSeconds: 90`** is good for SSE drain, but the Nginx edge only has 90s with no pre-stop lifecycle hook to drain connections. | `k8s/gateway/deployment.yaml:43`, `k8s/nginx/deployment.yaml:36` |
| M6 | **Kafka producer durability not visible in k8s.** No `min.insync.replicas` or `acks` in broker args or ConfigMap. Broker defaults apply. | `k8s/components/redpanda/statefulset.yaml:36-73` |

### LOW

| # | Finding | File:Line |
|---|---------|-----------|
| L1 | **Read replica has no startupProbe.** Could register as ready before PostgreSQL is accepting connections. | `k8s/postgres/read-replica.yaml:56-72` |
| L2 | **Social service has no HPA behavior block.** All other services define `scaleUp`/`scaleDown` behavior; social does not. | `k8s/social/hpa.yaml` (entire file) |
| L3 | **`k8s/kustomization.yaml` has duplicate realtime/personalization/growth entries** (lines 96-107 and 115-126). Kustomize may warn or ignore duplicates. | `k8s/kustomization.yaml:96-126` |

---

## 12. Production-Readiness Verdict

The codebase has **substantial infrastructure hardening**:
- Vault-backed ExternalSecrets with single-owner pattern
- Topology spread constraints on all workloads
- HPA + PDB on all 15+ services
- PITR with wal-g for Postgres
- Redis AOF + Sentinel for HA
- Redpanda 3-broker Kafka API with topic seeding
- Nginx edge with per-path rate limiting and security headers
- Startup + liveness + readiness probes on most services
- Load tests (k6) and chaos drills (Python) with CI integration

However, **critical gaps block heavy-traffic readiness**:
1. **PgBouncer is dead code** — not wired to any service, making the connection budget math invalid.
2. **Postgres primary is a single-replica Deployment** — no built-in failover.
3. **Duplicate volume key** in Postgres Deployment prevents deployment.
4. **Global ingress rate limit of 30 RPS** will throttle production traffic.
5. **No service mesh** despite documentation claiming Istio support.

These must be resolved before a heavy-traffic launch. The existing audit docs (`docs/HEAVY-TRAFFIC-PRODUCTION-READINESS-AUDIT.md`, `docs/PRODUCTION-READINESS-REMEDIATION-GUIDE.md`) correctly identify these and additional application-layer gaps (ShardAspect, Lettuce pool, HTTP client timeouts).

---

*Report generated from static analysis of k8s/ manifests, docs, and CI configs. No files were modified.*
