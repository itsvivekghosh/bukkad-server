# Bhukkad Backend-Server — Production Remediation Guide

**Version:** 3.0 (dependency-first execution order)  
**Date:** 2026-09-11  
**Scope:** Implementation-risk-assessed remediation program: 11 workstreams (W-1…W-11) sequenced by hard dependencies, each with detailed step-by-step guidance, verification gates, rollback paths, and regression guards. Supersedes all risk-category-ordered versions (v2.x).  
**Audience:** Senior Java engineers, platform, DevOps, security/compliance

### Version history

| Ver | Change |
|-----|--------|
| 1.0 | Original 19 finding-based guide. |
| 2.0 | Risk-dimension restructure after code cross-check. |
| 2.1 | Implementation-risk audit: CR-1…CR-8 prerequisites + rollback. |
| 2.2 | Full-detail expansion; new CR-9…CR-12 class issues. |
| 3.0 | **Round-3 re-verification applied.** Retractions/corrections folded in: CR-9 (an ingress *does* exist — claims rebuilt), SSE model corrected (servlet async, header-bound timeouts), pgbouncer claim fixed (deployed-but-unwired; no HPA exists), breaker-metrics claim refined (custom gauges exist; `resilience4j_*` series does not), ShedLock provider matrix confirmed, plus new production-critical findings: **CR-13** (ingress global `limit-rps: 30` silently caps every client IP), **CR-14** (relay and consumers share one gate with consumers defaulting `autoStartup=true` — flipping the backbone flag is structurally big-bang), **CR-15** (`EventBackbonePreflight` refuses `prod` boot with `enabled=false`, but shipped base manifests ship `false` + `SPRING_PROFILES_ACTIVE=prod` — so the manifests-as-shipped cannot boot prod, i.e. the deploy pipeline and the in-cluster state have silently drifted from the repo), and **H-14** (backup CronJob reads `S3_BUCKET` from the *live* ConfigMap whose value is `CHANGE_ME_BACKUP_BUCKET` — every scheduled backup since shipped has targeted a nonexistent bucket; RPO is effectively "nothing" and only `.last_success_epoch` masking hides it). Content reorganized from "by risk category" to **"by correct execution order"**, since a correct step done before its prerequisite becomes an incident. |
| 3.1 | 2026-09-11 | **Code implementation pass (verified):** CR-16 fixed — `k8s/nginx/configmap.yaml` upstream repointed `bhukkad-app:8080 → bhukkad-gateway:8080` (fresh-install 502s ended); Finding 8 closed — `DevAdminBootstrap` weak default removed, blank/short/known defaults fail boot; CR-18 fixed twice over — prometheus `CircuitBreakerOpen` expr repointed to the actually-exported `circuit_breaker_open{name}` series and `GatewayUnmatchedRoutesSustained` got its `_total` suffix, the custom-metrics HPA overlay retargeted from the ghost to `Deployment/bhukkad-order` with only-exported metrics (replaces base HPA by shared name, `min 3/max 20`); W-1.3 landed — `auth_lockout_active` records `outcome="failopen"` in both Redis catches + new `AuthLockoutFailOpenSustained` alert; Finding 12 closed — outbox `backoffFor` ±30 % `ThreadLocalRandom` jitter (band-tested) **after the discovery that `Retry.backoff(...)` already applies reactor's default 0.5 jitter — the v2.x "RetryFilter has no jitter" claim was wrong twice over and is corrected**; gateway `httpclient.pool` now an explicit fixed 500/pod budget (CR: implicit elastic ⇒ unbounded upstream connections). All green: `Tests run: 27, Failures: 0` (platform-lib) + identity `test-compile` + both `kubectl kustomize` renders + rules YAML parse. |

---

## How to Use This Document

**Read the dependency graph (§1). Do not reorder workstreams unless the graph allows it.** Each workstream defines:

1. **Objective & Why** — the failure mode being prevented.
2. **Prerequisites (hard gates)** — what must be proven done first (by ID).
3. **Step-by-step implementation** — verified against current code, with exact files/objects.
4. **Verification gate** — commands/checks that *prove* the step worked (tooling that exists on this stack — no `kubectl exec curl` into Temurin images; see D-15).
5. **Rollback & regression guard** — the way back, and the specific mistake that step invites.

Legacy IDs (O-#/S-#/SC-#/R-#, CR-#/HR-#/M-#) remain cited inside each workstream for traceability (Appendix G). All line references below were re-checked on 2026-09-11; Appendix G lists every prior claim that was **wrong and is corrected here**.

---

## Table of Contents

- [1. Why order matters — dependency graph + ratings](#1-why-order-matters--dependency-graph--ratings)
- [W-1 Ground truth & backdoor removal](#w-1-ground-truth-and-backdoor-removal-no-prerequisites)
- [W-2 Listener & relay gating — make enablement possible (CR-14)](#w-2-kafka-listener-gating-the-universally-blocking-fix-cr-14)
- [W-3 PostgreSQL: truth-check → durability (CR-1/CR-2)](#w-3-postgresql-truth-check-replication-durability-cr-1cr-2)
- [W-4 Data layer: N+1 + unbounded scans (SC-1/SC-2, HR-1)](#w-4-data-layer-n1-and-bounded-queries-sc-1sc-2)
- [W-5 The real edge: nginx ingress, TLS, streaming, autoscaling, budgets (CR-13…CR-19, H-15/H-16)](#w-5-edge-and-traffic-control-the-real-ingress-story-cr-13cr-19-h-15h-16)
- [W-6 Application resilience: breaker/jitter/locks/HA (CR-8, M-1/HR-9/HR-11, HR-18, O-4/O-5)](#w-6-application-resilience-breaker-status-jitter-locks-ha)
- [W-7 Money-path integrity & observability truth (S-3, O-6)](#w-7-writing-path-integrity-idempotency-s-3-and-honest-signals)
- [W-8 Security surface: auth/session/headers/secrets (S-1/S-2/S-6, R-1/R-2)](#w-8-security-surface-authn-headers-secrets-audit--s-1s-2s-6-r-1r-2)
- [W-9 Pipeline enablement — staged waves (O-1, now feasible)](#w-9-event-pipeline-enablement--the-staged-waves-it-was-designed-for)
- [W-10 Contract-safe modernization (S-4/§5.3, SC-9…SC-15)](#w-10-contract-safe-modernization-serialization-dtos-validation-s-4-53)
- [W-11 Close-out: compliance docs, CI/CD, chaos, catalog (R-4…R-6, O-7, D-set)](#w-11-close-out-cicp-compliance-verification-doc-enhancements)
- [Appendix A: Templates](#appendix-a-canonical-configuration-templates) · [B: Monitoring](#appendix-b-monitoring-alert-rule-registry) · [C: Checklist](#appendix-c-verification--go-live-checklist-final) · [D: Rollout](#appendix-d-rollout-windows-and-communication-plan) · [E: Compliance](#appendix-e-compliance-mapping-matrix) · [F: Metrics](#appendix-f-instrument-first-metric-alert-catalog) · [G: Risk register (complete, v3.0)](#appendix-g-implementation-risk-register--v30-final-supersedes-all-prior)

---

## 1. Why order matters — dependency graph + ratings

### 1.1 Readiness ratings (v3.0)

| Dimension | Rating | v3.0 rationale (with corrections) |
|-----------|--------|-----------------------------------|
| **Scalability** | **At Risk** | Read-path N+1 (`OrderService.java:396-402`; 25.5× query amplification), unbounded scans (`PublicBrowseController.java:104,139`), single-write PG ceiling. **Corrected SSE model:** `SseEmitter` is servlet async — idle streams don't pin workers; gateway `response-timeout: 8 s` bounds *time-to-headers* only (streams flush immediately). **Live edge ceiling (R4):** the served path is the `k8s/nginx` Deployment (2 replicas, `worker_connections 1024`, `upstream keepalive 32`) whose upstream Service is broken **in repo** (CR-16) and whose **per-IP caps (api_general 30 r/s, api_order incl. SSE-connect 10 r/s, stream `limit_conn 20/IP`) throttle CGNAT fleets well below plan targets** (the `k8s/ingress.yaml` limits are inert — no controller in repo, CR-13 re-scoped). **Connection wall (CR-19):** ≈840 per-pod Hikari warm conns vs PG `max_connections=500`, worsening at replica-scaled HPA max or routing ×2; no pod-level connection ceiling is enforced anywhere. No topology spread in any workload (verified). |
| **Robustness** | **Critical** | Backbone flag-off with a growing relay-free outbox while **consumers are unstart-stop-able (CR-14)**, `synchronous_commit=off` on a facade-replica primary (O-1), probes that report no dependencies, unrevocable JWTs, weak admin default (`DevAdminBootstrap.java:38`), breaker blind to HTTP status (5xx storms never trip it; `CircuitBreakerFilter` never inspects `ClientResponse`), no jitter (thundering herd), ShedLock no-ops in 12/15 services, deployed-but-dead PgBouncer, unauth'd `/actuator/**` pod-network exposure. |

### 1.2 Master dependency graph (arrows = hard gates; ✂ = parallel-safe)

| WBS | Goal | Depends on | Unblocks | Why in this position |
|-----|------|-----------|----------|----------------------|
| W-1 | Admin backdoor off; probe truth (no lies to build on) | — | everything | v2.0 lesson — CR-4: building failover on false health is how you engineer a self-inflicted outage |
| W-2 | Make consumer start/stop actually control-plane (CR-14) | W-1 verify | W-9 | **new**: no safe backbone enablement is possible without listener gating; was the hidden dependency of the whole pipeline program |
| W-3 | PG real durability + replication (CR-1/2) — decision → execution | W-1 | W-9 (outbox on primary), W-5 routing, SC-4 | all writes, money flows and outbox live on this process; the old "HA fix" advice would have *hung writes* |
| W-4 | Query-layer scalability | — | load headroom for waves | pure code (testable); independent of infra gates |
| W-5 | Real-edge controls (nginx zones, TLS chain, actuator/netpol, streams, autoscaling + **connection budget CR-19**) | W-1; CR-15 truth | W-6/W-9 | R4: the served path is `k8s/nginx` whose upstream Service is broken *in repo* (CR-16) and TLS is one commented-out line + an external cert-manager install (CR-17); limits/metrics/HPA artifacts are half-fiction (CR-18) |
| W-6 | App resilience mechanics (breaker status/jitter/locks/spread/Redis) | W-3 (lock table for ShedLock), W-5 (Redis decision) | W-9 | fail-fast + fail-open must be *coherent* before real event traffic arrives |
| W-7 | Idempotency envelope + honest metrics | W-2 (consumers), W-3 | W-9 wave 3, W-5 | money safety: never enable payment waves before dedupe is enforced and observable |
| W-8 | Auth surface, session revocation, headers, secrets, audit | W-1; W-7 for epoch cache story | prod traffic | revocation ordering (epoch-first) prevents JWKS mixed-fleet 401 storms (HR-13) |
| W-9 | Staged pipeline enablement waves (0–3) | **W-2, W-3, W-6, W-7** | W-10 | the culmination every prerequisite existed for: *relay on, consumers wave-by-wave* |
| W-10 | Contract-safe code modernization | W-7 | W-11 exit | Jackson payload swap is shape-fragile; DTO/validation sweeps are *breaking* |
| W-11 | CI/CD rewiring, drift, compliance docs, chaos | all | GA sign-off | automation codifies the state the humans just proved by hand |

Parallel-safe clusters: W-4 ∥ W-5; W-6 ∥ W-8 (except: epoch cache uses the Redis HA decision from W-6/O-5); within phases, verify gates from C never block the *next* phase's *start*.

### 1.3 Status table of the original 19 findings (as of v3.0, evidence-annotated)

| # | Finding | v3.0 status | Notes (with corrections) |
|---|---------|-------------|--------------------------|
| 1 | N+1 OrderService | Open | W-4 (two-step pattern; no paged `JOIN FETCH` — HHH000104) |
| 2 | `.env` secrets | Open (redefined) | files untracked+gitignored; real tracked artifact is `k8s/secrets.yaml`; history purge off unless audit proves a commit — W-8 |
| 3 | Idempotency write keys | Open | service (`IdempotencyService.java` Redis SETNX + Lua unlock; PG `insertIfAbsent` ledger) exists; no HTTP enforcement layer — W-7 |
| 4 | Unbounded scans | Open | also: "bounding-box prefilter" in nearby's javadoc is false (loads all rows) — W-4 step 1 |
| 5 | Scheduler pool | Done | `${SCHEDULER_POOL_SIZE:12}` verified 14/14 ymls + dedicated relay scheduler |
| 6 | PG SSL | Open | `ssl=false` `configmap.yaml:27,39`; W-3-6 sequencing (certs after replication, never before) |
| 7 | Container security contexts | Partial (4/15) | W-5-A.5 template |
| 8 | Weak admin password | **Done (2026-09-11 code pass)** | Default removed; blank/`<12`/known-default password fails boot (`DevAdminBootstrap` ctor); identity test-compile green |
| 9 | Load-balanced WebClient | Done | `WebClientConfig.java` correct `reactive.` classpath string (verified); k8s DNS + per-target bounded pools |
| 10 | Response DTOs | Open | W-10 under §5.3 contract rules (golden corpus first) |
| 11 | HikariCP pools | Done | incl. delivery env; min=max warm-idle budget feeds W-3-5 |
| 12 | Jitter backoff | **Done (2026-09-11 code pass)** | **Correction: `Retry.backoff(...)` already applies reactor's default 0.5 jitter — the v2.x claim "RetryFilter has no jitter" was wrong;** the true no-jitter path was the outbox — `OutboxProperties.backoffFor` now ±30 % `ThreadLocalRandom`, cap preserved, band-assert test (27/27 green) |
| 13 | Jakarta validation | Open | blocked by error-envelope harmonization (W-10 step 0, CR-11) |
| 14 | Jackson payloads | Open | **shape-frozen**: `amount` string + `status` string contracts are load-bearing (`OrderLiveEventConsumer.java:122`) — W-10 |
| 15 | Raw Maps → DTOs | Open | W-11; dual-key PageResponse |
| 16 | Controller logic | Open | W-10/11 |
| 17 | Redis/Kafka health | Open | W-1 (truth) + W-6 (cached AdminClient) — informational body only, never probes |
| 18 | CSP/headers | Open (evidence corrected) | ingress exists but has **no CSP annotation**; service-direct traffic bypasses both gateway & ingress ⇒ platform-lib filter is load-bearing; edge gets annotation too — W-5 |
| 19 | Unify SSE | Done-but-scaling-open | uniform `SseEmitter` verified; **corrected** capacity model (async, not threads-per-stream); remaining work = registry consolidation + capacity controls — W-5 |

---

## W-1: Ground truth and backdoor removal (no prerequisites)

**Objective:** no subsequent work is built on wrong signals: health probes say what they mean, the default admin door is gone, and per-dependency alerts reflect reality.

### W-1.1 Remove the `DevAdminBootstrap` fallback (Finding 8) — one PR ✅ DONE 2026-09-11

**Why:** `services/identity/src/main/java/com/bhukkad/identity/config/DevAdminBootstrap.java:37-38`: `@Value("${app.bootstrap-admin.password:Test@123456}")` (≈52-bit, ~2 h at GPU rates), fixed email default `admin@bhukkad.dev`. Gated by `@ConditionalOnProperty("app.bootstrap-admin.enabled", havingValue="true")` — one stray `true` in any prod context = immediate backdoor, and the first pod that runs seeds a *permanent un-patchable* ADMIN row.

**Steps:**
1. Edit `DevAdminBootstrap`:
   ```java
   @Value("${app.bootstrap-admin.password:}") String password
   ...
   if (password.isBlank()) throw new IllegalStateException(
       "app.bootstrap-admin.password must be set when bootstrap-admin is enabled");
   if (password.length() < 12) throw new IllegalStateException("min length 12");
   ```
2. Keep the `enabled=false` default pinned for prod (`application-prod.yml` already lacks the flag ⇒ absent = off).
3. Policy gate — CI grep in `staging.yml`/`production.yml` + a kubeconftest rule denying `app.bootstrap-admin.enabled: "true"` under any prod-context overlay (same class as `SecretValidationConfig` fail-fast).
4. Local dev ergonomics: document `./scripts/run-local.sh` example passing `-Dapp.bootstrap-admin.password=…` (v2.2 §8 step 4 text), so removing the default doesn't silently break the dev loop.

**Verify:** start identity with `enabled=true` and blank password → fail-fast on the first restart attempt; grep the deployed env set (`kubectl exec … env` replaced by `kubectl set env --list` — the env-var surface needs no shell).
**Guard (HR):** if staging DBs *already* hold the seeded `admin@bhukkad.dev` user, rotate it to a random secret and invalidate tokens for that user (see W-8 epoch).

### W-1.2 Probe semantics contract (`LoginLockoutService.java:141-145,164-167` as design oracle)

**Contract (written as ADR — D-18):**
- **Liveness** `/actuator/health/liveness` = `livenessState` only. A process that can't answer is *restarted* — never for dependency reasons.
- **Readiness** `/actuator/health/readiness` = `readinessState,db` **only where the request path genuinely stalls on the DB** (order, payment, restaurant…); gateway adds config-readiness only. Redis and Kafka are **never in probes**: the whole app is built to fail them open (login lockout returns `0`/`null` and logs; idempotency uses PG ledger; the gateway limiter runs a 1 s Redis budget — `EdgeRateLimitFilter`). A probe that drains every pod during a Redis blip is a *worse incident than the blip itself* (this replaces v2.0's O-3 wiring).
- **`/health/detailed`** = operator/diagnostic truth surface (HTTP 200 with per-component fields), **not** wired to kube probes. Currently lies: `HealthController.detailed()` hardcodes `"status":"UP"` while adding DB/memory (file is 149 lines; Redis/Kafka members don't exist; `dbReplica()` returns literal `UP`/`NOT_CONFIGURED`).

**Steps:**
1. Add Boot group config fleet-wide (order template):
   ```yaml
   management:
     endpoint:
       health:
         probes: { enabled: true }
         group:
           readiness: { include: readinessState,db }   # per-service; only if serving truly needs DB
           liveness:  { include: livenessState }
   ```
   (Verify against the existing probes: `k8s/order/deployment.yaml:95-106` already targets the two `readiness/liveness` group paths — no YAML change needed, just the group definition and honest indicator.)
2. Rewrite `HealthController` members:
   - `database`: already real (SQL `validationQuery`); keep. 
   - `redis`: `template.hasKey` / PING via cached connection; `NOT_CONFIGURED` when no template bean.
   - `kafka`: **one cached `AdminClient` bean** — construct lazily, reuse; 500 ms `describeCluster()`; negative `lastError` cached with age (HR-8). Never per-request `AdminClient.create`.
   - Roll up: `status = min(overall)` but always HTTP 200 (this body is diagnostic); readiness group does the eviction.
3. **Delete the stale readiness claim** in `k8s/redis/deployment.yaml:35-39` (says the readiness group is `readinessState,db,redis` — no such config exists anywhere).
4. Alerts instead of eviction: add `bhukkad_dependency_up` series from the health controller's own metrics or a micrometer gauge set; `DependencyUnhealthy` alert (Appendix F) pages — the customer-surface synthetic (W-7) decides "serving", probes only decide "this pod".

**Verify (drills — the only way trust is built):**
```bash
# Redis down for 60 s:
kubectl scale deploy bhukkad-redis --replicas=0 -n bhukkad   # staging only!
kubectl get pods -n bhukkad -w        # all services stay Ready/Running; lockout logs failopen; DependencyUnhealthy fires
# Primary DB stall (kill -STOP postgres) during an upgrade rehearsal:
# pods whose readiness includes db go NotReady; non-db services keep serving (gateway 503s from no endpoints = visible, correct)
# restart rollout: no NotReady loop (the old "false-UP, real 500" class fixed)
```
**Rollback:** pure config; delete the `group` lines to restore Boot defaults.
**Guards (regression):** (a) adding `redis/kafka` into *readiness* "for safety" re-introduces CR-4 — policy: kubeconftest denies readiness groups containing anything but `readinessState`/`db`; (b) per-request Kafka admin-client churn is a broker-DoS generator (HR-8) — enforce singleton construction in review.

### W-1.3 Fail-open must be visible (S-6, M-4) ✅ counter+alert DONE 2026-09-11

**Why:** `LoginLockoutService`'s Redis catches (lines 141-145: `recordFailure` returns 0 on `RedisConnectionFailure|RedisSystemException`; 94-101 assertAllowed treats null TTL as open; 164-167 `lockedFor` fail-open). Correct availability math — wrong observability: a Redis outage silently *opens* the brute-force window, and the metric (`auth_lockout_active`) never notices (only `deny`/`activate` outcomes).

**Steps:**
1. In both catch blocks: `record(METRIC_AUTH_LOCKOUT_ACTIVE, "failopen")` (+ `log.debug` detail already present as WARN) with `outcome` tag.
2. Alert: `rate(auth_lockout_active{outcome="failopen"}[1m]) > 0` sustained 60 s → warning; the gateway's own fail-open path (`EdgeRateLimitFilter` 1 s Redis timeout + fail-open javadoc) gets the mirrored counter at the gateway level.
3. Document the accepted-risk register: lockout counters are **Redis-resident** ⇒ (a) outage ⇒ window open; (b) Sentinel failover with async lag ⇒ counter/strike keys can vanish mid-attack (resets escalation). Mitigation backlog: secondary IP-keyed lockout (`EdgeSecurityInterceptor` uses real `remoteAddr` — spoof-safe per LoginLockout javadoc 187-191; extend to count auth failures per-IP) — M-4, scheduled W-8-6.
**Verify:** unit test with injected template failure asserts counter `failopen`; drill: scale Redis down, drive failed logins, see the metric but confirm logins still *work* (fail-OPEN, not fail-shut).
**Guard:** "fix" = making lockout fail-closed would 100% lockout everyone on a Redis blip — reject; keep observability-only change.

### W-1 verification gate (exit criteria)
- identity refuses boot with blank/enabled admin config; CI denies the flag.
- readiness/liveness group YAML merged in all 15 services + drill transcripts archived.
- `redis`/`kafka` health members implemented as cached singletons; `/health/detailed` returns per-component truth; stale redis Deployment comment deleted.
- `failopen` metric live + alert wired (Appendix F row).

---

## W-2: Kafka listener gating — the universally blocking fix (CR-14)

**Objective:** convert the monolithic `APP_EVENTS_EXTERNAL_ENABLED` toggle into per-service and per-listener control, so W-9's waves actually exist. Today, no such control does.

**Why (evidence):** the gate is **one shared expression covering everything**: `KafkaPlatformConfig.java:61-63` (which owns `@EnableKafka`, the `kafkaListenerContainerFactory` with the DLT error handler, and the token-auth consumer factory) and `OutboxPlatformConfig.java:43-45` (relay) are both `@ConditionalOnExpression(enabled=='true' && type=='kafka')`. Consumers themselves carry **no** control attributes at all — every `@KafkaListener` is bare (`PaymentSagaEventConsumer.java:71-73`, `OrderLiveEventConsumer` same; grep-verified: zero `autoStartup`/`containerFactory`/`spring.kafka.listener.auto-startup` fleet-wide). Consequences:
1. `enabled=false` → `@EnableKafka` never processes → all consumers genuinely inert. *(The "silently running" worry from v2.2 is retired — worse is true instead:)*
2. `enabled=true` → **relay + every consumer container auto-start in the same boot, atomically, per service.** Listener autoStartup defaults TRUE ⇒ there is no way to flip the backbone up "relay-only": the money consumers (`PaymentRequestedConsumer`, `PaymentSagaEventConsumer`) join simultaneously. W-9's waves — and v2.2's claimed "wave 0" — are structurally impossible without this workstream (CR-14).
3. **CR-15 (repo-vs-reality contradiction, resolve first):** `EventBackbonePreflight.java:67-78` *refuses* Spring-profile-`prod` boot whenever `enabled=false`, yet the shipped base is self-contradictory on purpose: `configmap.yaml:22 SPRING_PROFILES_ACTIVE: "prod"` + `:58 APP_EVENTS_EXTERNAL_ENABLED: "false"` and every pod consumes both via `envFrom` (e.g. `k8s/order/deployment.yaml:44-46`). As-composed, **prod pods cannot boot** — so either the live cluster diverges from the repo (a manually patched ConfigMap — invisible to GitOps and to all of this guide's rollback drills), or the pipeline is in fact already enabled. First step of W-2 is therefore **truth reconstruction**: `kubectl get cm bhukkad-config -o jsonpath='{.data.SPRING_PROFILES_ACTIVE}{.data.APP_EVENTS_EXTERNAL_ENABLED}'`, per-pod effective env (`kubectl set env --list ds/deploy` — no exec needed), and `kubectl get pods` state. Whatever it returns, reconcile the repo to reality *before* any gate change: the preflight's stated goal ("a disabled-but-healthy pod silently loses every event") only holds if what's in git matches what's running.

**Steps:**
1. **Audit the factory binding:** confirm `KafkaPlatformConfig.java:59-65` (`listenerContainerWithAuth` is expression-gated per its own comment) is *not* the default factory name (`kafkaListenerContainerFactory`) — establish exactly which container factory the listeners get, and what its lifecycle does today.
2. **Split the single gate into relay vs consumer control** (this is the actual fix): the shared `@ConditionalOnExpression` makes relay, publisher, *and all listener containers* a single on/off unit. Introduce `app.events.external.consumer-startup` (default **`false`** when absent, i.e. "backbone on = relay/publisher on, consumers stay down until explicitly started"):
   - On the gated platform factories (`KafkaPlatformConfig` `kafkaListenerContainerFactory` + the token-auth factory, `:128-143`): `containerProperties.setAutoStartup(consumerStartupEnabled)` — one place, covers every bare `@KafkaListener` without editing 9 classes individually.
   - Add per-listener unique `id`s regardless (`id = "payment.saga"`, `"order.live"`, …) so the `KafkaListenerEndpointRegistry` can start/stop groups individually (runtime waves, no restart): expose a small authenticated admin op (platform-lib `KafkaLifecycleService`, `ObjectProvider`-safe): `start(id)/stop(id)/state()` — with a boot log line listing every container id + its current started/stopped state.
   - Per-deployment wave control = env: `APP_EVENTS_EXTERNAL_CONSUMER_STARTUP=true` + optional `APP_EVENTS_CONSUMER_START_IDS=payment.saga,order.saga` — W-9 overrides via env, never code.
3. **Boot-time consistency preflight** — mostly already built and *should be trusted*: `EventBackbonePreflight` fails prod boot unless `enabled && kafka`, which is the anti-silent-loss guard from v1. Keep it, and extend it with (a) log of effective consumer-startup mode + started ids, (b) broker-reachability probe (`AdminClient.describeCluster().nodes().get(5s)` on the singleton) so `enabled=true` mis-configured fails fast too (mirror `SecretValidationConfig`).
4. **Poison-event discipline pre-checks** (already strong: `ConsumerRetrySupport` DLT+keyed, `PoisonEventException` pattern; `KafkaPlatformConfig:55` comment: "an exception is what routes a poison event to the DLT") — assert each listener actually maps malformed input → `PoisonEventException` per the `OrderLiveEventConsumer` contract, so waves 1–3 can't wedge a group by retry-looping a bad record.
5. **Unit + integration tests:** (a) flag-off context: relay bean + kafka beans *exist* (enabled=true path) but registry containers report `isRunning()==false`; (b) `consumer-startup=true`: containers running; (c) `stop(id)` halts intake without pod restart and the group goes away on heartbeat timeout (document the rebalance window); (d) relay-true/consumers-false = "wave 0" is now an emplaceable system state, provable from a test.

**Verify (staging):** with backbone enabled + consumer-startup unset: outbox pending drains (`bhukkad_outbox_pending` → 0), lag stays N/A (groups absent — check `kafka-consumer-groups.sh --list` **absent**, not empty), then start wave-1 ids one group at a time and re-check.
**Rollback:** stop all ids (or `CONSUMER_STARTUP=false`) + rollout — a paused container consumes nothing, and relay keeps running so no events lost (they buffer on the broker + outbox).
**Guards (regression):**
- **Listener ids must be unique per app context** — duplicate ids across classes in one service (two listeners on `order.events.v1` with generated names) cause the registry map to collide and `stop(id)` to stop the wrong container. Set explicit distinct `id`s in every `@KafkaListener` in the same PR that introduces `consumer-startup`.
- **autoStartup=false containers still *register*** — that's expected; they simply don't poll. Don't confuse "registered" with "running" in wave checks (assert via `isRunning()`, not bean presence).
- **Starting a listener on only *some* pods** of a multi-replica service creates group-imbalance (one pod does all partitions until rebalance) — waves are per-group (all pods of a service), per-deployment env, *not* per pod; document to prevent cargo-culted `kubectl set env` on a single replica.
- Keep `@EnableKafka` inside the same class as today — the annotation processes listener *endpoint registration*; if the gate is false there is no factory + no registration; only change container **startup**, never the conditional structure (changing that structure reintroduces bean-existence races).

### W-2 exit: platform-lib exposes per-listener ids + factory-level autoStartup property + registry start/stop + boot state logging; tests green; wave 0 provably reachable; repo-vs-cluster CR-15 reconciled (single source of truth in git); W-9 unblocked.

---

## W-3: PostgreSQL truth-check → replication → durability (CR-1/CR-2)

**Objective:** a *real*, well-understood PG posture. Today the guide's own v2.0 advice would have hung every write; the manifests misdescribe themselves.

### W-3.0 Facts as verified 2026-09-11 (design basis)
- `k8s/postgres/deployment.yaml:10` single replica; `:16` `Recreate`; PVC `ReadWriteOnce`; `synchronous_commit=off` (`postgres/configmap.yaml:22`); `max_connections=500` (`:17`) against 15×20-pool + routing-on 2× ⇒ headroom is thin.
- `read-replica.yaml`: 2-pod **StatefulSet with no `volumeClaimTemplates`**, no `standby.signal`/`primary_conninfo` ⇒ pods self-`initdb` on ephemeral storage and serve **empty DBs** while `pg_isready` passes → "facade".
- `k8s/configmap.yaml`: replica advertised at `bhukkad-postgresql-replica` — **no such endpoint exists** (`k8s/postgres/service.yaml` exposes only the primary; replica Service is `bhukkad-postgresql-read`). `k8s/pgbouncer` is deployed in base (`kustomization.yaml:30-32`) but `SPRING_DATASOURCE_URL` renders `bhukkad-postgresql:5432` — **PgBouncer is wired to nothing** (no service consumers; the earlier claim of a pgbouncer HPA is corrected in Appendix G).
- `DataSourceConfig.java` (routing, round-robin, primary-fallback) + `ReadReplicaSelector` are complete **but unconfigured** (`app.datasource.read-replica.*` zero hits fleet-wide).

### W-3.1 Prove (do first, 15 min)
```bash
psql "$PRIMARY" -Atc "select count(*) from pg_stat_replication"                     # expect 0
psql "$PRIMARY" -Atc "select application_name,state,sync_state from pg_stat_replication"  # empty
kubectl -n bhukkad exec bhukkad-postgresql-read-0 -- env | grep -i 'PGREPL\|REPLICA' || echo "no standby wiring"
kubectl run psql --rm -i --restart=Never -n bhukkad --image=postgres:16-alpine -- \
  psql "postgresql://bhukkad-postgresql-replica:5432/bhukkad?sslmode=disable" -c 'select 1'  # expect: NXDOMAIN (name absent)
```

### W-3.2 Decide ONE design (ADR; D-18)

**Option A — Real async streaming (incremental, honest):**
1. Add to read-replica.yaml: per-pod **storage** (`volumeClaimTemplates: [{ storageClassName: standard, accessModes: [ReadWriteOnce], resources.requests.storage: 5Gi }]`);
2. **Standby genesis as init-container:**
   ```
   pg_basebackup -h bhukkad-postgresql -D /var/lib/postgresql/data-base -U repl --wal-method=stream -R --no-password
   ```
   with a `.pgpass` secret mount (`$HOME/.pgpass` or `pgpassfile`) — *do not* rely on the `01-init.sql` heredoc: the bootstrap's `CREATE ROLE repl … PASSWORD '$POSTGRES_REPLICATION_PASSWORD'` (configmap `postgres/configmap.yaml` ~line 49) inserts an **unexpanded shell var literally** into SQL — mount a rendered secret or use `PGPASSFILE`. (M-11)
3. Primary conf additions (configmap): `wal_level=replica` (already default 16 but pin it), `max_wal_senders=10`, `max_replication_slots=10`, `hot_standby=on`; size `wal_keep_size` **against** `max_wal_size=2GB`: a lagging replica pins WAL ⇒ watch `pg_wal` growth — add an alert for slot WAL retained > 1 GB (M-11 mitigation).
4. **Name alignment:** rename the Service to `bhukkad-postgresql-replica` *or* repoint `k8s/configmap.yaml:37-39` to `bhukkad-postgresql-read`; one side changes, CI-grep asserts no `*-replica` host survives that resolves to nothing (and no consumer uses `read` when the app says `replica`).
5. Keep `Recreate` + 1-replica primary (RWO makes `RollingUpdate` impossible — CR-2; a stuck surge pod is the classic failure). Write-availability RTO = volume re-attach; record it.
6. `synchronous_commit` stays **off**; document RPO = "recent post-crash commits can be lost". If RPO≈0 is genuinely needed later, that's Option B (operator with real sync standby), not config fiddling — CR-1: `FIRST 1 (replica-group)` today = **deadlock** (zero standbys registered + non-empty names = each commit waits on a ghost).
7. Routing go-live (deferred until A is proven; §W-9 note): configure per replica URL + `read-only` props; **flow-safety pass required**: today's `@Transactional(readOnly=true)` read-after-write flows — order list right after create, wallet balance right after top-up, menu after publish — *must not* route to a lagged replica (HR-17 read-your-write violations) ⇒ route browse/search/admin-analytics first; app-layer primary-pin for customer read-after-write until the staleness audit clears each flow.

**Option B — Managed HA:** CloudNativePG 3-instance, `synchronous_mode`, auto-promotion, PITR wired to the existing `backup-cronjob.yaml`+`scripts/ci/restore-drill.sh`; retires hand-rolled `k8s/postgres/*`. Recommended end-state; higher change volume.
**Option C — "sync commit now": REJECTED**, recorded (deadlock proof in CR-1/Appendix G).

### W-3.3 Connection budget (decide WITH PgBouncer, SC-4/HR-10/HR-12 corrected)
Today both are half-states: direct 15×20=**300 potential conns** vs `max_connections=500` (fine at idle, hot at 13-routing services ×20 = ~600 worst-case if routing flips) **and** a deployed-but-unused pgbouncer. Pick: (a) delete `k8s/pgbouncer/*` + kustomize lines (simplicity; budget fits if idle pools reduced — min-idles are 20/service ⇒ real conns ≈ idle 20×15 + burst), or (b) **adopt** pgbouncer transaction pooling (`service` exists; repoint URLs to `bhukkad-pgbouncer:6432`) and set `default_pool_size`, `max_db_connections` (primary 60-120), and **accept the transactional-pooling semantics**: no session `LISTEN/NOTIFY`, no `SET` persistence, prepared statements via pgbouncer's own handling, advisory locks OK; also note Flyway + `SET LOCAL` flows need review; add `query_wait_timeout` + saturation metrics; then the `idle_in_transaction_session_timeout` = 0 choice in v1 Appendix C must be revisited (aborted tx leak). Document whichever is chosen in this guide + ADR.
**Never** run both half-wired (current state).

### W-3.4 Backup integrity gate (H-14-class)
`backup-cronjob.yaml:112`: pg_dump all services' DBs + redis rdb + `aws s3 sync` to `s3://${S3_BUCKET}/…` where `S3_BUCKET` resolves from **`configMapKeyRef: bhukkad-config` (`backup-cronjob.yaml:120-123`)** — and that ConfigMap value is `S3_BUCKET: "CHANGE_ME_BACKUP_BUCKET"` (`configmap.yaml:86`). **Consequence: every daily "backup" since that manifest shipped has been syncing to a bucket named `CHANGE_ME_BACKUP_BUCKET`, i.e. nowhere.** This is a P0 data-protection defect (RPO = nothing), masked because the job itself reports success only via `.last_success_epoch` per-container flags (the sync step's failure path must be audited). Actions: (a) point `S3_BUCKET` at the real bucket via `bhukkad-secrets` (same source as credentials — one secret, no template-shaped keys), kill the configmap key ("remove dead key" becomes "remove live-but-dead key"); (b) add `sync ... --expected-statuses` hard-fail + a `.last_sync_success` marker; (c) run `scripts/ci/restore-drill.sh` (referenced by `docs/backup-and-restore-runbook.md`) as a *success* proof, not just a dry run; (d) wire the existing `RestorableBackupMissing` alert (`prometheus-rules.yaml:158-165`) to a metric that the cronjob actually pushes (appendix F rule: no alert without exported source).

### W-3.5 SSL wiring (Finding 6) — the certs already ship; flip the component (revised by W-5.2)
- **New fact from W-5.2:** `k8s/components/tls-internal/` defines `bhukkad-internal-ca-issuer` + **`bhukkad-postgres-tls`/`bhukkad-redis-tls`/`bhukkad-redpanda-tls`/`bhukkad-nginx-tls` Certificates (90-day auto-renew, SANs for in-cluster Service DNS)** — the component is **commented out** at `k8s/kustomization.yaml:146`. Server certs/pipeline are therefore *already built*; the work is enablement + client wiring, not cert issuance from scratch. (The `cert-manager` base install remains a manual Day-0 per k8s README + `k8s/cert-manager.yaml` absent-from-tree caveat (H-15) — without it those Certificate resources never resolve.)
- SANs must match the **names clients actually hit**: `bhukkad-postgresql.bhukkad.svc.cluster.local` (+ chosen pgbouncer name if adopted post-W-3.3; verify the certificate dnsNames list literally covers Service *and* headless forms before trusting `verify-full`).
- Primary enablement: mount `bhukkad-postgres-tls` into the postgres pod, `postgresql.conf` `ssl=on` + cert/key paths, `pg_hba.conf` `hostssl` for app roles; restart; then flip per-service JDBC — `configmap.yaml:26-27` to `?ssl=true&sslmode=verify-full` + truststore mount of the internal CA secret (`sslrootcert=`).
- **Verify** with a checker pod (D-15): `psql "…&sslmode=verify-full" -Atc 'show ssl_is_used?…'` → `select count(*) from pg_stat_ssl join pg_stat_activity using (pid) where ssl = false;` = 0 rows after rollout; watch `HikariCPConnectionsPending` during cutover.
- `pg_hba.conf`: `hostssl` for app roles; container config mount; JDBC: `ssl=true&sslmode=verify-full&sslrootcert=…` (mount CA into the shared volume pattern from Appendix A template, one source); rotate apps in batches observing connection resets.
- **Verify** with psql checker pod (D-15): `select ssl from pg_stat_ssl … where not ssl` = 0 rows.
**Rollback:** `sslmode=disable` via configmap + rollout; no state to unwind.
**Guards:** don't add `ssl` to readiness; don't put server certs into the *client* truststore path that Flyway migrations create at boot (no migration coupling).

### W-3 exit: A or B signed in an ADR; `pg_stat_replication` >0 or operator `cluster status` healthy; restore drill transcript filed; naming budget doc updated; SC-4 chosen; SSL verified.

---

## W-4: Data layer: N+1 and bounded queries (SC-1/SC-2)

Code-only; testable anywhere; independent of W-3 except the final routing flip.

### W-4.1 N+1: two patterns (never page a collection fetch — HR-1)

**Step 1 — Repository methods:**
```java
// services/order/.../OrderRepository.java — customer list (bounded, non-paged pattern)
@Query("""
  SELECT DISTINCT o FROM Order o
  LEFT JOIN FETCH o.items
  WHERE o.customerId=:customerId AND o.status IN :statuses
  ORDER BY o.createdAt DESC
  """)
List<Order> findRecentForCustomer(@Param("customerId") Long customerId,
                                  @Param("statuses") List<String> statuses,
                                  Pageable pageable);   // Pageable ⇒ LIMIT/OFFSET on root only when NOT a collection fetch
```
For list pages where a collection join page *is* wanted, use canonical **two-step**:
```java
@Query("SELECT o.id FROM Order o WHERE o.restaurantId=:rid AND o.status IN :st ORDER BY o.createdAt DESC")
Page<Long> orderIds(@Param("rid") Long rid, @Param("st") List<String> st, Pageable p);

@Query("FROM Order o LEFT JOIN FETCH o.items WHERE o.id IN :ids ORDER BY o.createdAt DESC")
List<Order> hydrateWithItems(@Param("ids") Collection<Long> ids);
```
→ exactly 2 queries per page, bounded memory. `getOrdersForCustomer/Restaurant` become `ids → hydrate → map`.

**Step 2 — Drop per-order item loads** in projections:
```java
// OrderService.toResponse(Order): use order.getItems() (fetched) not orderItemRepository.findByOrderId(id)
// OrderOpsController.kitchenQueue()/pendingOrders()/myDeliveries(): ids+hydrate path (no stream over findAll-style lists)
```
**Step 3 — Fallback batching** where fetch-joins can't reach: `@BatchSize(size=20)` on `Order.items` — one chunked `IN` per page.
**Step 4 — Regression lock (D-11):** `@DataJpaTest` + `datasource-proxy`/p6spy (staging profile) asserting ≤2 statements for a seeded 20-order fixture; run before/after in the PR (numbers in description).

### W-4.2 Unbounded scans: bounded first, PostGIS *after* the W-3 image decision (CR-6)

**Step 1 (no DB extension risk — ship first):**
- `filterPublic`: Specification predicates (`isActive`, `isPureVeg`, cuisine **via join on `cuisineId`** — the schema is FK-id-only) + `PageRequest(20, max 100 clamp)` ⇒ `findByIsActiveTrue()` and per-request `cuisineRepository.findAll()` deleted (`PublicBrowseController.java:101-117`).
- `listPublic`: keep, but the `Pageable`-bounded path exists — delete the unbounded `findByIsActiveTrue` usage and force `MAX_PAGE_SIZE` clamp (already present — preserve).
- `nearby`: fix the javadoc lie with a *real* SQL bbox prefilter: `latitude BETWEEN :latMin AND :latMax AND longitude BETWEEN :lngMin AND :lngMax` (+ **index** `idx_restaurants_active_coords ON restaurants(is_active, latitude, longitude)`) ⇒ Java-side Haversine only over the ≤100 candidates; keep `LIMIT` semantics (`MAX_BATCH_IDS` today) — deterministic.
- AdminOps fraud dashboard: `@Query("select e.status, count(e) from FraudEvent e group by e.status")` replaces three full loads; add index for the grouped column if missing.
- Cart/GroupOrder same class: `findByCartId(...)` bounded (`Pageable` or hard `limit`), menu `getMenuItem` N+1 per §10 fix.

**Step 2 — PostGIS: gate on W-3:**
- (a) switch DB to `postgis/postgis-alpine:16` (rebuild of `k8s/postgres/deployment.yaml:25` image) ⇒ then: guarded DO-block (`EXCEPTION WHEN undefined_object`-style) inside an **ops Job, not Flyway** (CR-6: shared-cluster Flyway failure ⇒ 15-service boot failure), geometry column + trigger + `GIST` index, native query `ST_DWithin(geo,ST_MakePoint(:lng,:lat)::geography,:m)`; service picks PostGIS **only behind a one-time startup probe** (`SELECT extname FROM pg_extension…` cached).
- (b) keep (a)-path permanently if the image swap is refused (bbox path is honest and cheap).

**Verification (each PR):** `EXPLAIN (ANALYZE, BUFFERS)` before/after in the PR body; staging p6spy: query count and rows-per-request flat vs restaurant count growth; no `Seq Scan` on `restaurants(is_active=true, cuisine…)` after index creation (`EXPLAIN` shows bitmap/index scan).

### W-4.3 API bound policy
Every list/GET returns ≤ MAX_PAGE_SIZE (100 here; 200 cap elsewhere) *server-side*, page clamps (`0..100_000` today is a DoS vector — clamp page too or use cursor); `size` is a string param today — type it and reject with a clean 400 (W-10 error envelope).
**Guard:** pagination changes are contract changes (empty page 3 not today’s semantics) — mobile contract tests first (§W-10.2); never "just returns fewer rows" silently without deprecation notes.

---

## W-5: Edge and traffic control: the real ingress story (CR-13…CR-19, H-15/H-16)

### W-5.1 The live edge is `k8s/nginx` — and it points at a Service that doesn't exist (CR-16)
**Truth first (replaces the CR-13 framing):** the repo contains **two competing edges**:
- `k8s/nginx/deployment.yaml` (replicas: 2, `type: LoadBalancer` service, `worker_processes auto` / `worker_connections 1024`) — **this is the entry path that actually serves traffic** (config in `k8s/nginx/configmap.yaml`);
- `k8s/ingress.yaml` (`ingressClassName: nginx + cert-manager annotations`) — **nothing in-repo implements it** (grep: no ingress-nginx controller manifests); it is inert unless the cluster pre-installs a controller out-of-band.

**CR-16 (P0 — found this pass):** nginx's upstream is `server bhukkad-app:8080 max_fails=3 fail_timeout=30s; keepalive 32` (`k8s/nginx/configmap.yaml:107-109`) — **no Service named `bhukkad-app` exists in this repo** (also no Deployment, so both W-5.1 and W-5.5 share one ghost). The gateway's Service is `bhukkad-gateway` (`k8s/ingress.yaml:38-46` confirms `bhukkad-gateway:8080` is the intended backend). Consequence: fresh `kubectl apply -k k8s/` bootstraps an edge 502ing everything. **Fix:** upstream → `bhukkad-gateway.bhukkad.svc.cluster.local:8080`; then prove with `kubectl get svc bhukkad-app -n bhukkad` (NotFound expected) and one HTTP request end-to-end post-install. (Same ghost target also breaks the HPA overlay — W-5.5.)
*And the CR-13 truth check:* the `k8s/ingress.yaml` `limit-rps: 30` is **inert** — no ingress-nginx controller ships in-repo (`grep -rln ingress-nginx k8s/` empty) ⇒ the real caps customers hit today are the **nginx zone tables** above (api_general 30 r/s burst defaults, `api_auth 5 r/s`, `api_order 10 r/s + burst`, stream `limit_conn 20/IP`) with **`limit_req_status 429`**. They carry the identical CGNAT/reconnect-storm problem (see W-5.4 item 1) and must be re-sized at the *nginx* layer; then either delete `k8s/ingress.yaml` (if keeping the sidecar edge) or vendor a real ingress-nginx + reconcile one authoritative place for limits — **a half-truth edge in git is worse than none.**
**Heavy-traffic implications (all must be sized in an ADR):**
1. **CGNAT per-IP ceilings:** 10 r/s and 20 concurrent streams per client IP means an office/apartment block on shared egress gets fractionally throttled at trivial real volume; the gateway's Redis-backed limiter already does better classification (`EdgeRateLimitFilter`, incl. `edge-login 10/60s`) — **edge limits must be loosened to DDoS-floor values (e.g. 3-5× the gateway budgets) or removed for auth'd paths**, with the gateway as the authoritative limiter.
2. **SSE reconnect-storm amplification:** order-stream 429s trigger client reconnect loops *at exactly the wrong moment* — 429 + `Retry-After` semantics for SSE endpoints must be client-documented (k6 storm test in W-5.7 covers it).
3. **Concurrency budget:** `worker_connections 1024` × 2 replicas ≈ ~2k half-open pairs ⇒ the 10k-stream registry target is unreachable at the edge before it is reachable in Java; add nginx HPA (today **static replicas: 2**) + node-connection monitoring, or document the edge as the binding capacity number.
4. nginx has **no PDB/HPA/securityContext** in-repo (check) — fold into the O-4/SC-16 sweeps.
**Fix list:** (a) upstream name; (b) per-zone budgets vs gateway (delete the root Ingress or install + reconcile a single edge in-repo — never keep both); (c) `limit_conn` for stream zones sized ≥ 50/IP; (d) add HPA or capacity ADR for nginx; (e) verify reload semantics (ConfigMap change ⇒ `nginx -s reload` — does the deployment probe/auto-reload? add checksum annotation to pod template so changes rollout).
**Verify:** checker-pod `curl -vI https://api.bhukkad.com/health` cert chain OK; missing-clusterissuer drill = cert secret events show failures — the alert should fire *before* users hit redirect loops.

### W-5.2 TLS truth (CR-17 — replaces the v3.0 "external cert-manager" story)
The repo already builds a full internal TLS stack — but **ships it commented out**:
- `k8s/components/tls-internal/certificates.yaml`: four cert-manager `Certificate`s from a `bhukkad-internal-ca-issuer` — **`bhukkad-postgres-tls` (SANs on the postgres headless/per-pod DNS), `bhukkad-redpanda-tls` (all broker pod DNS, headless + svc, 90-day auto-renew), `bhukkad-redis-tls`, `bhukkad-nginx-tls`**;
- `k8s/kustomization.yaml:146`: `# - components/tls-internal  # ← uncomment this line to enable TLS`; `:170` warns app configs (JDBC `ssl=false` etc.) only flip when the component is enabled; `k8s/ingress.yaml:6` references `letsencrypt-prod` + cert-manager, **absent from the tree, with no ingress controller manifest anywhere** → the Ingress object is inert today.
**Consequences for "very heavy traffic":** public edge is currently plain HTTP on the nginx LoadBalancer (:80); the browser↔edge TLS most people assume from `ingress.yaml` does not execute; and the internal CA (even when enabled) is not browser-trusted — nginx's 443 file (`bhukkad-api-tls`) would present a cert chains to an in-repo CA only mobile apps pinning it could verify. **Decisions (ADR):** (1) enable `components/tls-internal` ⇒ wire PG/Redis/Redpanda mTLS or starttls server+client (pairs with W-3.5 for PG, and the Redpanda 9093/9094 JWT listener already in its StatefulSet: brokers exist with a 2nd listener whose client-side SASL/SSL use is not wired — verify); (2) public trust: **pick one** — a real ingress-nginx + cert-manager deployed and the `bhukkad-tls` chain cut over, or an L4 LB + ACME sidecar, or TLS at nginx with the `bhukkad-tls` secret (LE-issued cert outside k8s) — and keep the Ingress object until then *deleted or honestly commented*, never a half-truth; (3) `force-ssl-redirect` semantics on both paths must yield 301→https or 426, verified end-to-end (checker-pod `curl -v http://api.bhukkad.com/` after cutover).
**Never conflate:** browser↔edge trust ("public chain") ≠ service↔PG/Redis/Kafka (`tls-internal` CA + JDBC/SSL client config).

### W-5.3 `/actuator/**` and exposure parity (H-16)
- **Probes work:** `order/SecurityConfig.java:44` permits `/health/**,/actuator/**` — kubelet (no token) passes; same pattern spot-check each service before rollout (some SecurityConfigs may differ — identity/notification are the sensitive ones).
- Real issues: pod-network scrape endpoints open **to all pods** (metrics info leak + unauth'd surface); actuator exposure drift (`admin-analytics/delivery: health,info,metrics,prometheus` vs gateway/growth/identity `health,info,metrics` ⇒ Prometheus panels silently half-covered); bearer token absent though comments claim otherwise (`service-monitors.yaml` — O-7 fix).
**Steps:** narrow permitAll to `/health/**, /actuator/health/**` (keep liveness/readiness groups reachable); expose `prometheus` consistently via ServiceMonitor bearer/`authorization` (token secret or pod labels — the actuator is already inside mTLS-less flat network, so at minimum make the target discoverable with credentials) OR declare metrics unauth'd-but-networkpolicy-scoped (the **netpol sweep** was flagged earlier: `networkpolicy.yaml` must actually select *all* DBs — audit: the postgres Service vs policy selector names!).

### W-5.4 SSE/streaming capacity (SC-17 **fully corrected model**)
Claims retired: (v1/v2.0) 8 s gateway cut applies to *headers* (SSE flush immediately); Tomcat workers are **released on async start** (no thread-per-stream); the Ingress 3600 s settings never execute (no controller — W-5.1/5.2) — the **live** stream path is nginx `location ~ ^/api/v1/orders/stream/` with `proxy_buffering off`, `chunked on`, **`proxy_read/send_timeout 360 s`**, and `limit_conn` **20/IP** (W-5.x above). Binding limits in order of impact:
1. **Per-IP concurrent-stream cap (20) + api_order zone 10 r/s on the connect** — with CGNAT this is the first wall a heavy-surge deployment hits (an office of 200 users can hold 20 streams). Raise conn-per-IP for stream zones (≥50 like the general zone), or key by JWT subject at the gateway, not IP at the edge.
2. **Tomcat async ceiling:** `threads.max=100` bounds *fan-out send concurrency*, `maxConnections` (default 8192)/`accept-count 200` bound sockets — per-stream **heap** + registry caps (`maxTotal=10 000`, per-key 100) are the working limits: keep them, **export `sse_connections` gauge from order too (it emits none today; realtime/delivery already do — CR-18's canonical-name fix)** + admission-reject beyond N.
3. **Gateway upstream pool is unconfigured** (`httpclient` block has timeouts only, `gateway/application.yml:17-19`) ⇒ default provider sizing (version-dependent, not the doc's earlier "200" guess) carries all REST + one long-lived connection **per open proxied stream** — set explicit `spring.cloud.gateway.httpclient.pool` (type=fixed, max-connections e.g. 500/pod, pending-acquire-timeout ~10 s, max-idle-time) before capacity claims are meaningful.
4. **Edge own capacity:** `worker_connections 1024 × auto workers × 2 replicas`, **no HPA on the nginx Deployment** — 10 k streams need replicas/pair-budget raised in step with services (add nginx to W-6.5 spread + HPA scope).
5. **Fan-out discipline:** heartbeat (keep 25–30 s ≪ 360 s read timeout) prunes dead emitters; consolidate registries into platform-lib `DefaultSseStreamRegistry` + `LiveOrderEvent` DTO interface (v2.2 plan); Flux migration stays **optional polish** — async already frees workers; its real win is heap/replay semantics (gate behind its own ADR + 10 k-stream soak vs today).
**Verify (public path only):** 1 k streams + 300 rps REST through LB→nginx→gateway→order (k6 SSE/Gatling — current `loadtest/k6/load-test.js` is REST-demo only): zero unexplained 429s (reconnect-storm proof), gateway pool `active/pending` bounded, `jvm_threads` flat (async), gateway per-stream connection footprint recorded, edge connection < worker budget.

### W-5.5 Autoscaling overlay is dead fiction today (CR-18)
`k8s/overlays/custom-metrics-hpa/` (comment header: self-admits the previous copy *never built*), after its own "fix", still: `scaleTargetRef → Deployment bhukkad-app` (**a ghost name — no such Deployment exists**, same legacy artifact as the W-5.1 upstream) with metrics `http_server_requests_seconds_count_orders` and `sse_active_connections` — **neither is an exported/prometheus-adapter series here** (code: `sse_connections` gauge + `sse_capacity_rejected_total` in realtime/delivery only; nothing in order — CR-18). Applying it changes nothing silently → false "waves can scale" confidence. **Fix list:** repoint scaleTarget per-service Deployment; rename metrics to what the apps export (register once, canonical); add order gauge (item 2); only then apply overlay (its REQUIREMENTs comment is right — adapter first, CPU fallback only until then); add nginx HPA (§1.2 W-5-4).

### W-5.6 Connection budget at scale (CR-19 — decides PgBouncer in W-3.3, not taste)
Hikari budget is **per pod**: 14 DB-owning services × 3 replicas(HPA floor) × `${X_DB_POOL_SIZE:20}` ≈ **840 potential warm conns vs PG `max_connections=500`** (`k8s/postgres/configmap.yaml:13-17`) — with read-replica routing ON ⇒ ×2/pod ≈ 1 560 target; during a rolling deploy +`maxSurge`s. Even pre-routing, HPA scale-up alone (order→12, search→… +17 pods) can add ~+340 conns. Today it "survives" only because pods are few; at very heavy traffic the **500 wall is hit first, cluster-wide** (and each service's `connection-timeout: 10000`/3000 then fails the request path, not the DB). Choose in ADR: (a) **PgBouncer transaction** (deployed already!) sized `≈ 0.7×(500 − slots)` servers, apps → 5432→6432 with pool caps kept for *app-local* latency, budget math redone with surge + Flyway/backup (`pg_dump` slots) reserved; or (b) raise server budget & recompute per service `pool ≤ 500/(Σeffective_pods)` with a hard CI-check. Either way the A.1 idle-vs-max policy + `query_wait_timeout`-style saturation alerts are mandatory.
**W-5 exit:** upstream `bhukkad-app` bug fixed + single edge declared (CR-16); TLS story decided (CR-17); actuator/netpol unified (H-16); per-IP caps sized for NAT reality + SSE public-path soak green; gateway pool configured; overlay rebuilt against real Deployment names with exported metric names (CR-18); connection budget signed (CR-19 → W-3.3).

---

## W-6: Application resilience: breaker status, jitter, locks, HA (CR-8, HR-9/M-1/HR-11, HR-18, O-4/O-5)

### W-6.1 Circuit breaker: record what the HTTP contract says fails (M-3 corrected) — rule retarget ✅ 2026-09-11

**Implemented this pass:** `prometheus-rules.yaml` `CircuitBreakerOpen` now queries the real
`circuit_breaker_open{name}` gauge (plus `GatewayUnmatchedRoutesSustained` fixed for its missing
`_total` suffix — same never-firing class). The 5xx-inspection change + `sse_capacity_rejected_total`
order-side export remain open (they need release-gated behavior change, not just YAML).
`CircuitBreakerFilter` currently records transport/timeout as failures and success for everything else (`filter(...)` never inspects `ClientResponse.statusCode`). A downstream that fails **without exception** (200 with empty body, 504 via HTML) never trips the breaker — retries then hammer it.
```java
// v3.1: map status into the decoration window
.filter(request, next).timeout(CALL_TIMEOUT)
.transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
.flatMap(resp -> {                        // AFTER operator records success — see guard note
    if (resp.statusCode().is5xxServerError())
        return resp.releaseBody().then(Mono.error(new UpstreamServerError(resp)));  // recorded as failure
    return Mono.just(resp);
}).onErrorResume(CallNotPermittedException.class, e -> Mono.just(
    ClientResponse.create(SERVICE_UNAVAILABLE).header("X-Circuit","open").build()));
```
**Guard (ordering — subtle):** R4J records per `Mono` signal; converting a response *after* `CircuitBreakerOperator` records the call as success means the mapping must sit **inside** the decorated chain (wrap the whole exchange: `.transformDeferred(CircuitBreakerOperator.of(cb))` *around* the response-status mapper — write the test first: upstream stub 500 → breaker opens in ≥failure-rate cases; retry filter must not retry 5xx via the old silent-success hole (`RetryFilter` already retries only when an exception carries it — verify `isTransient(Throwable)` covers `WebClientResponseException` statuses: it does (408/5xx, from earlier code `status >= 500` check at `RetryFilter.java:87-95` — that half was *never broken*; the gap is purely the breaker).
**Metrics binder (verified R4 exact):** the factory passes the app `MeterRegistry` (`PlatformWebClientBuilderFactory.java:136-138`), and when present the filter registers **`circuit_breaker_open{name}` (1/0)** and **`circuit_breaker_state{name}` (ordinal 0–6)** gauges (`CircuitBreakerFilter.java:64-79`) — but `prometheus-rules.yaml:88` queries **`resilience4j_circuitbreaker_state{name,state="open"}`**, the R4J-micrometer naming the static-registry construction never produces. The alert is dead *as written*, not unmeasured: **fix = repoint the expr to `circuit_breaker_open{name} == 1`** (or adopt the R4J names with `TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(...).register(registry)` if a standardisation is preferred — pick one, Appendix F records it).
**Bulkheads (M-6/§18):** bounded per-target WebClient pool (64) is shared capacity across *all* calls to a target; a slow external (Twilio 5 s) throttles internal order→restaurant calls if same target — they aren't today, but OSRM/Razorpay-style singletons deserve `BulkheadRegistry` semaphore per target (config `resilience4j.bulkhead.instances.osrm.maxConcurrentCalls`). Add `TimeLimiter` only where `mono.block` remains (`blockQuietly` = 20 s saga RPC holding a DB connection — **M: move the whole saga RPCs out of the DB tx** is the real fix; flag as async-saga completion work, tracked as M-14).

### W-6.2 Jitter everywhere (Finding 12) ✅ DONE 2026-09-11 (+ claim corrected)
> The outbox path (`OutboxProperties.backoffFor`) was the only sync point and now carries
> ±30 % `ThreadLocalRandom` jitter (band-asserted test); **v2.x's "RetryFilter has no jitter"
> was wrong** — reactor's `Retry.backoff(...)` applies a 0.5 jitter factor by default.
> Kafka listener DLT retry uses `ExponentialBackOffWithMaxRetries` (3 tries, 500 ms ×2, 5 s cap —
> `KafkaPlatformConfig.java:161-165`; no `random` factor set, but total exposure is ≤ ~7 s/record
> before it parks on the DLT, so no herd window to close; setting `setRandom(0.3)` is free polish).
1. `RetryFilter`: `.jitter(0.5)` — Reactor built-in (v1's concern about `.jitter()` existence: it's on `RetryBackoffSpec`; add + test spread like v1 §12 unit sketch with seeded randomness).
2. Outbox `backoffFor`: `ThreadLocalRandom.current().nextDouble(0.7, 1.3)` around exponent — **no `SecureRandom` per attempt** (entropy contention + hotspot); no Math.random alias.
3. Kafka retry `ExponentialBackOffWithMaxRetries` already has Spring's multiplier + maxInterval — jitter: replace with `new BackOffExecution` wrapper or accept R4J retry yaml jitter where used (delivery osrm `jitter` field — confirm property names in yaml).
4. Alert `RetryStormDetected` (exists) now meaningful (with W-3.2 metrics).
**Math (why):** 100 pods retrying a down PG at `1,2,4 s` synchronized = 100 requests every 7 s in phase; uniform [0.5×,1.5×] removes the resonance ⇒ recovery window shrinks from ~N cycles to ~1–2 per client.

### W-6.3 Distributed locks matrix (HR-18 **resolved**)
Providers exist: delivery/growth/search (`PlatformConfig/GrowthSchedulerConfig/SearchSchedulingConfig` — `LockProvider` beans). **Absent** where `@SchedulerLock` already annotates methods: platform-lib `IdempotencyCleanupScheduler` (component-scanned into all services), `order/` sweeps, `admin-analytics` (`DataRetentionService:75` annotation with only the DB side) etc. Effect there: every pod of that service runs the sweep concurrently (idempotent but duplicate load; the comment itself concedes it: "without a provider deletes are idempotent anyway").
**Steps:** promote the delivery `PlatformConfig` provider to platform-lib `@ConditionalOnMissingBean(LockProvider.class)` + `shedlock` table migration to all Flyway baselines (single ownership!), then verify via two-pod race logs (`SHEDLOCK` row updates). Document per-job which are safe (deleteBatch is) vs which weren't (retention scans, reconciliation sweeps).

### W-6.4 Redis HA alignment (O-5) — with Spring client notes
Current: Sentinel ×3, monitor `bhukkad-master … 2` quorum **but a single-replica `Deployment` master** ⇒ no failover possible; RWO PVC, Recreate. Options (ADR): **(A)** master→headless-Service StatefulSet (`serviceName`) + `--replica-of` + PVCs, keep sentinels ⇒ **client change required**: `spring.data.redis.sentinel.master` config on 10 services (currently flat `REDIS_HOST` → verify each app's env); `min-replicas-to-write 1/min-replicas-max-lag 10` to bound data loss; **(B)** drop Sentinel trio (misleading theater), fast-recreate single pod, **declare the keyspace volatile by design** (idempotency is PG-backed for money; lockout/rate budgets are best-effort already per LoginLockout fail-open + `max_batch 5000` deletes; SSE presence is ephemeral) — with a runbook: post-failover window = lock/strike reset (W-1.3 M-5 note).
Either way, probes stay off Redis (W-1 contract). If (A): update `service-monitors`/`redis-exporter` target names; the sed-based sentinel auth (`redis-sentinel.yaml:35` password templating) — brittle (`&`, `/`, multiline breakage) ⇒ use mounted secret file or a generated conf ConfigMap at deploy (HR-17).

### W-6.5 Topology spread & anti-affinity (O-4)
Template per A.5 (v2.2) **first with `ScheduleAnyway`** (prevents unschedulable pods during the change itself; verified currently *absent* from every manifest), audit node/zone topology (`kubectl get nodes -L topology.kubernetes.io/zone`) then flip to `DoNotSchedule` once nodes ≥ 2×replica. **PDB correction — all 15 service PDbs already ship (verified `k8s/*/pdb.yaml` ×15): the work is review (minAvailable vs HPA floor/rolling surge), not addition.** Add nginx/pgbouncer/redis/postgres spread checks — nginx (2 replicas) is the most exposed (whole edge on one node).
**Verification gate:** `kubectl get deploy -ojson … | jq 'select(has(topologySpread) | not)'` empty; node-drain drill keeps each service ≥1 Ready (that's the actual test — the "spread config" alone proves nothing).

**W-6 exit:** status-aware breaker test green; jitter spreads in a 100-client failure sim (fail-and-count log timestamps histogram attached); locks exercised under 2-pod concurrency; Redis design signed; spread verified by node drain.

---

## W-7: Writing-path integrity: idempotency (S-3) and honest signals

Money dedupe **before** W-9 wave 3. Two layers:
1. **Envelope filter (contract):** OncePerRequestFilter, `app.idempotency.mode = off|honor|require` (flag-gated, honor ships first); key `(scope, Idempotency-Key)` scope per finding-3 list (order-create first). **Fixes all v1 draft bugs**: wrapper capture via `ContentCachingResponseWrapper` registered pre-handle, committed body stored after `chain.filter` returns (never an interceptor `afterCompletion` — CR-5); cached `{status, mediaType, body}` replayed with **original status** (+`Idempotent-Replayed: true`); in-flight duplicate ⇒ `409` only while `status=PROCESSING`, never fake `200`; failed 5xx ⇒ `release/drop` the claim so retries are possible (4xx stays cached); body-size cap (100 KB; **SSE/file endpoints out of scope**); money scopes (payment, wallet, gift-card, loyalty-credit) **fail closed** on Redis *and* claim-store unavailable: a claim-store outage is exactly when duplicates breed ⇒ 503 with `Retry-After` (non-money may fail-open).
2. **Persistence:** `lock:` (TTL) + claim-row `insertIfAbsent` (already built — `IdempotencyRecordRepository:60`) + response `UPDATE`; cleanup batched (`deleteBatch` drains — but **recalculate against 100 req/s**: 5 000 × 12 runs/hr = 60 k/h drained vs 360 k/h created ⇒ fix cadence/batch or per-shard sweepers; add `idempotency_backlog` gauge + `IdempotencyCleanupOverrun` alert — M-2).
3. **Client contract (A.3):** single UUID per logical order attempt, same key on network retry **across cold-starts (persistence caveat)**; docs for third-party API consumers.
4. **Verification:** property-based ITs: sequential dup (`201`→`201`), concurrent dup (exactly one side effect), in-flight (`409` then final), crash mid-request (in-flight claim expires → retry recomputes → dup guarded by unique business key at DB where it must be) — the last case *must* remain safe ⇒ money stores' own `idempotency_key UNIQUE` on business tables is the real backstop (check orders/payment).
5. **Metrics:** replay-hit rate; `idempotency_key_missing_total` in honor mode = client migration KPI; wire into `bhukkad_idempotency_*` Appendix F before `require` enforcement (rollout needs the number, not vibes).

### W-7.2 Synthetic monitor — staging-only (HR-5 corrected)
Prod order creates spend **real wallet ledger** (async-saga `WALLET` method + growth/loyalty accrual + survey trending) — the previous prod plan contaminated balances. v3: staging k6 `order-create→cancel` cron (5 min) with its own test customer (excluded via growth tagging), success/latency gauges for `SyntheticOrderFailure`. Prod keeps read-only health-path checks only, no order writes.

**W-7 exit:** honor-mode deployed behind flag; IT suite green; backlog alert configured; staging synthetic job live; money-store unique index proof (one SQL check).

---

## W-8: Security surface: authn, headers, secrets, audit (S-1/S-2/S-6, R-1/R-2)

1. **R-1 secrets** (v2 §2 sequence, corrections applied): history purge *only if* `git log` proves `\.env` commits (current: untracked — the audit is 5 commands and gates the rewrite decision); `k8s/secrets.yaml` retired to `.example` (ESO owns the Secret); `.env*` remain local dev tools but **never** in the ESO path; rotation in same window as JWKS cutover; gitleaks+trufflehog fs scan in CI; verify build-context via `docker build` w/.dockerignore test.
2. **S-2 JWT revocation/rotation (HR-2/HR-13 corrected design):**
   - **Step 1 — token epoch (no per-request Redis):** `users.token_epoch`, `epoch` claim, auth compares against **local Caffeine/TTL-cached (60 s)** user epoch — revocation "eventually ≤60 s", zero hot-path Redis. Password change / admin disable / lockout-escalation bump epoch.
   - **Step 2 — refresh tokens → Postgres** (`token_hash`, `family`, `expires_at`, `revoked_at`): reuse detection (present-but-revoked ⇒ **revoke family**, page security); rotation 30-day → revocable; the "30-day refresh survives compromise" risk is thereby fixed *at the source* rather than by hot-path bitsets.
   - **Step 3 — JWKS keypair rotation** (`kid`, cached JWKS + `X-Request-Id`-safe invalidation): rotation = sign with new key; verifiers accept both during **max-TTL+skew overlap**; **do not dual-verify mixed-fleet with JWKS-only** — legacy no-`kid` HMAC path must remain until every fleet has rolled (HR-13).
   - **Step 4 (optional, only above 15 k daily tokens):** exact Redis *set membership* of revoked JTIs (not Bloom: FP direction wrong for revocation, HR-2) + local LRU; TTL = token min-TTL.
   - Verification: revocation latency budget test (≤60 s + propagation), rotation drill (no downtime), family-reuse alarm fires.
3. **S-1 headers (CR-9 corrected, HR-3):** platform-lib filter is authoritative (service-direct traffic bypasses gateway **and** ingress — the only choke that isn't). API-safe: `default-src 'none'`/frame-ancestors none/base-uri none/form-action none, `X-Frame-Options: DENY` + `X-Content-Type-Options` (keep), `Permissions-Policy` (keep; gateway copy ok), HSTS **only where the edge serves** (ingress owns `force-ssl-redirect` today; duplicate HSTS at 7 layers = dedupe pain — `DedupeResponseHeader` already exists, add the names), drop `X-XSS-Protection`, **never `preload`** (irreversible + our TLS chain isn't proven yet, H-15); report → enforce two-step; unit: mock response headers, plus a **golden-HTTP contract header test** (D-11). Gateway keeps echo-CORS + `Vary` + cache-control (it does, `GatewaySecurityHeadersConfig.java:42-51`).
4. **S-4 →** W-10 (Jackson payload contract).
5. **S-6 →** W-1.3 (lockout fail-open metrics) + **new (M-4)**: secondary IP-keyed lockout counter via remoteAddr (LoginLockout's javadoc:186-191 anti-XFF choice is correct — reuse the posture) with its own fail-open observability.
6. **R-2 (audit events):** Redpanda topics via the existing topics-init pattern (`docs/event-catalog.md` + `k8s/components/redpanda` job — **not Strimzi CRs**, CR-7): `platform.audit.v1` (12p, RF3, 13.1-month retention) with outbox-transaction emission (same tx discipline as business events, claim-in-tx), retention/object-lock archival; consumers = SIEM exporter (external) — no new probe/alert dependencies. Emit from: auth success/fail-with-identity, admin bootstrap, epoch bumps, payment refunds/wallet writes, retention purges (`DataRetentionService` counts).

**W-8 exit:** rotation drill green; header contract test green; secrets audit closed; audit topic producing to staging SIEM.

---

## W-9: Event pipeline enablement — the staged waves (O-1)

**Prerequisites: W-2 (per-listener controls + CR-15 reconciliation) + W-3 (replica/WAL budget for relay load) + W-6 (breaker/jitter/locks) + W-7 (money idempotency).** This ordering existed in v1 as a wish; it was *impossible under CR-14*, and CR-15 says the repo can't agree on whether prod boots disabled at all.

0. **Reconcile CR-15 first** (done in W-2): the base manifests + profile now define one coherent truth in git — backbone enabled (preflight satisfied), consumers paused. If instead ops chose the disabled-on-boot path, the preflight must be amended in code (not just the cluster) before this wave program proceeds.
1. **Topics:** verify every catalog topic + DLT pair exists on Redpanda (topics-init job); partitions match consumer plans; **RF3** for money/delivery; retention sized so wave delays can't orphan history. **Partition cap now has a number (R4, `k8s/components/redpanda/topics-job.yaml:5-16`): `order.events.v1` and the default `bhukkad.platform.events` are 6 partitions (DLTs 1:1), and the job is *create-only, never alters existing* — so order/payment/realtime groups can never have more than 6 concurrent consumers.** Wave 3 money consumers + order `maxReplicas 20`: decide per ADR either pre-creation bump (recreate topics is destructive for unretained streams — only viable before wave 0 first publishes) or **cap HPA max to ~6 per consuming group** and let per-pod CPU do the REST scaling. Document the choice beside the wave table.
2. **Wave 0 — relay only, zero consumers:** `APP_EVENTS_EXTERNAL_ENABLED=true` + `type=kafka` + **`APP_EVENTS_EXTERNAL_CONSUMER_STARTUP` unset (false)** ⇒ relay+publisher live, every listener registered-but-not-started (W-2 design). Watch `bhukkad_outbox_pending` → 0 (drain budget: relay default = 30/5 s ≈ 360 min/pod — for a multi-day backlog temporarily `batchSize=100, pollInterval=2s`, restore after drain; **the processingTimeout > batch-drain invariant (HR-11) is boot-enforced after W-2**). Assert `kafka-consumer-groups --list` shows **no** wave groups — the wave-0 state is now *provable*, not assumed.
3. **Preflight bean** (extended per W-2-3): enabled ⇒ broker reachable + types match + started-ids log — prevents half-enabled services.
4. **Wave 1 (read-only consumers):** start `search-sync` then `survey`/`notification` groups (per-deployment env `…CONSUMER_STARTUP=true`/ids, one service at a time) — 48 h soak each; lag + DLQ ≈ 0; poison counters at zero.
5. **Wave 2:** analytics CQRS, realtime fan-out, delivery.
6. **Wave 3 (money, gated on W-7 exit):** `payment` `PaymentRequestedConsumer`, order `PaymentSagaEventConsumer`. **Why last and why it matters (money-critical):** order's `application-prod.yml` already runs `async-saga.enabled=true` ⇒ on real traffic, capture = `payment_requested` outbox publish → payment consumer → `payment_settled` back → `PaymentSagaEventConsumer` CONFIRMs. Backbone off = consumers not even registered (this is CR-15's flip side); backbone half-on = orders park in `AWAITING_PAYMENT` and mass-cancel via the sweep at `stuckOrderMinutes:15` — a *silent refunds/stuck-orders incident by config drift alone*. Exit proof, staging first: synthetic order at `sweep-interval-ms=10000` → reaches CONFIRMED, sweeper cancels nothing; only then prod ladder.
7. **Rollback per wave:** stop the affected listener ids (W-2 registry control / deployment env) — **flag-revert is NOT the rollback**: under profile `prod`, `APP_EVENTS_EXTERNAL_ENABLED=false` makes `EventBackbonePreflight.java:74-78` refuse boot → crash-loops the whole fleet (the trap the old rollback note contained). Consumers stopped ⇒ broker + outbox buffer (retention-sized); relay continues ⇒ nothing is lost.
**Verification:** per-wave soak report archived; `bhukkad_outbox_dead_letter` ≈ 0; lag < pollInterval×batch; async-saga e2e evidence (stage + prod canary); backup/restore unaffected during drain (PITR still valid throughout — WAL was budgeted in W-3.2).

---

## W-10: Contract-safe modernization: serialization, DTOs, validation (S-4, §5.3)

### W-10.1 Jackson payloads (CR-10: **shape-frozen**, not "improve it")
- **Freeze fixtures before touching code:** capture today's literal payloads per event type (`{"orderId":123,"status":"CONFIRMED"}` etc.) from staging logs/DLQs + unit fixture files (`OrderEventPublisherTest` pattern) that assert *byte-equal* keys/types (`status: string`, `amount: plain-string decimal`, epoch-less `ISO_LOCAL_DATE_TIME` for survey).
- DTO records serialize to same (`@JsonProperty` where field names differ, `WRITE_BIGDECIMAL_AS_PLAIN` for amounts as **strings** — keep the string contract payment expects, `OrderEventPublisher:63-64`).
- **Dual-read compatibility:** consumers already `FAIL_ON_UNKNOWN_PROPERTIES=false` (`ObjectMapperConfig.java:16-19`, `PlatformEventMessage:26`) and `PaymentSagaEventConsumer` maps `payload` as JsonNode ⇒ migration = producer-side only ⇒ ship per-producer feature flag (`app.events.mapper=jackson|string`, default string), canary one pod, diff consumer lag + DLQ, then flip fleet; rollback without redeploy.
- Consumers that *are also producers* (payment replies via same envelope) ⇒ Pact (`feature-ci.yml` pact jobs) + **add consumer-driven contract tests before any field change**; document additive-only version rules (`OrderLiveEventConsumer` frozen-shape comments :112-120 = your contract text).
- Sweep remaining builders: `sagaPayload` (`OrderService.java:267`), both notification string writers, group-order Map builders (`HashMap`+`put` — §15) — each with golden-diff tests.

### W-10.2 DTO/validation controller sweep (Finding 10/13/15/16), in contract-safe order
0. **Error-envelope harmonization (CR-11 first):** one `@RestControllerAdvice` shape repo-wide (`code, message, fields[]`) mapped from `BusinessException` + `MethodArgumentNotValidException` + ConstraintViolation ⇒ **before any @Valid lands on existing endpoints** (otherwise clients get Boot whitelabel mid-migration). Golden bodies from staging first (D-17 corpus).
1. `PageResponse<T>` **dual-keyed during migration** (old `page/content/totalElements` + new `items/…`) with client migration window + deprecation header (`Deprecation: true`) — raw-Map surfaces today: browse/envelopes/group orders/identity addresses/device tokens.
2. Jakarta annotations per DTO (`@NotNull @Positive @Size(min=1,max=50)`…) remove manual checks **after** the advice exists (message text parity so existing client error-copy tests pass — today's exact string is `"Order requires at least one item"`, `OrderService.java:77`; map `items` empty-violation messages to it byte-for-byte, not to Boot defaults).
3. Business logic extraction (cart haversine/cache/envelope → `RestaurantQueryService`/`OrderQueryService`, v2 detail retained).
4. ArchUnit (freeze-ratchet): new rules `no RestController returns Map/entity` with **freezeLists** per service (existing violators grandfathered, CI fails on *new* violations + shrinks on migration); tests wired to `mvn verify` gate.
**Verification:** k6/golden HTTP contract suite (new D-17 harness) diffs every migrated endpoint's pre/post response (schema-diff tool or Pact), zero non-additive changes without an issue-linked ADR.

---

## W-11: Close-out: CI/CD, compliance, verification, doc enhancements

### W-11.1 CI/CD (O-7)
1. Canonical build: services.yml per-service job ⇒ `docker build -f services/docker/Dockerfile.service --build-arg MODULE=<svc> services/`; remove the stale `services/<svc>/Dockerfile` refs + absent-root `docker/Dockerfile` from production/staging workflows (line refs v2.1);
2. **Immutable rollouts:** record digests (already captured `:118-123`), flip `DEPLOY_BY_DIGEST`; manifests: `kubectl set image deployment/... ghcr.io/...@digest` via `kustomize edit set image` at render time — **drop `imagePullPolicy: Never`** or it fights digests on new nodes (verify `:latest`+Never today = fragile);
3. Cosign verification step optional-only today (`COSIGN_KEY` present? `:133-149` skipped ⇒ decide: require);
4. **Verify-all-services:** production/staging rollout loops → all 12 k8s services + their ServiceMonitors; post-deploy smoke `e2e-smoke.sh` domain list extended (realtime/search/personalization/…) currently 8 endpoints only;
5. nightly-regression: `mvn verify`-equivalent, per-module path fixes (`services/*/target`; CI test-failure artifact path), ArchUnit fail-if-no-ran (`-Dsurefire.failIfNoSpecifiedTests=false` is *hiding* a vacuous green — remove after the suite exists), Pact publish wired (`PACT_BROKER_*` used by `production.yml:86-89-verified-consumercontract` ⇒ run must consume that), mutation/sonar thresholds real;
6. `kubectl kustomize` smoke (already exists) + kubeconftest policy pack of this guide (readiness-group, topology-present, :latest-denied-new-lines, admin-flag-denied, lockprovider-present).

### W-11.2 Compliance & data rights (R-3…R-6)
policy doc + DSR script (`GET/DELETE /api/v1/admin/dsr/{user}` with two-person approval + audit events (R-2)), DPA ledger table, scheduled dependency-check/SBOM job with CVSS≥9 fail→7 tighten after backlog, ZAP baseline (feature-ci already carries OWASP job).

### W-11.3 Program verification (run against Appendix C checklist)
Full chaos pass: DB primary delete, redis failover (per chosen design), Kafka broker kill, synthetic money-path drill; 1000-user load vs. W-4 metrics; `outbox_events_*` alerts fire in staging for every Appendix F alert name; restore drill with new PITR state; evidence freshness stamps (D-16 bot) re-verifies the line citations in this doc.

### W-11.4 Doc set (D-1…D-19)
D-1 threat model diagram (+ boundary note on service-direct traffic), D-2 compliance matrix (E), D-3 glossary, D-4 reference architecture (README already lists domains — C4 L2), D-5 `docs/runbooks/` (DB failover, Redis, outbox drain, DLQ triage — `ConsumerRetrySupport`'s own comment already prescribes the "operator runbook" artifact; write them), D-6 metric catalog = Appendix F here, D-7 k6 scenario set incl. SSE/soak, D-8 ADRs required: W-3.2 choice, Redis A/B, pgbouncer A/B, JWKS plan, SSE-Flux decision; D-9 compat matrix per W-10, D-10 flag convention (`app.<service>.<capability>.enabled` — precedent: `app.order.async-saga`, `app.idempotency.mode`, `app.events.mapper`), D-11 header + error-golden tests, D-12 dev-env script (`scripts/dev-env.sh` printing Vault-read env, never writing `.env`), D-13 baseline dashboards JSON, D-14 PR template with prerequisite checkboxes; D-15: verification commands must run in real jobs (`curlimages/curl` checker pods) — **all execs in this guide are checker-pod style**; D-16 line-evidence freshness bot; D-17 golden corpus tooling; D-18 probe-state infra ADRs; D-19: this doc's Appendix G is the audit trail; quarterly, re-run the greps and re-date.

---

## Appendix A — Canonical configuration templates

### A.1 HikariCP baseline (reference: already rolled; governs future services)

```yaml
spring:
  task: { scheduling: { pool: { size: ${SCHEDULER_POOL_SIZE:12} } } }   # Finding 5: verified fleet-wide (14/14 ymls), keep
  datasource:
    hikari:
      maximum-pool-size: ${ORDER_DB_POOL_SIZE:20}
      minimum-idle: ${ORDER_DB_POOL_SIZE:20}           # W-3: budget check — warm idle × services must fit maxconns
      connection-timeout: 10000      # (was 3-5s) avoid false 500s on queued contention *unless* PgBouncer chosen:
```

### A.2 Probes (v3: per-service groups + kubelet paths)
```yaml
management:
  endpoint:
    health:
      probes: { enabled: true }
      group: { readiness: { include: readinessState,db }, liveness: { include: livenessState } }
livenessProbe: { httpGet: { path: /actuator/health/liveness, port: http }, periodSeconds: 15, failureThreshold: 3 }
readinessProbe: { httpGet: { path: /actuator/health/readiness, port: http }, periodSeconds: 5 }
```

### A.3 Idempotency — one client rule
```javascript
key = sessionStorage["idemp:"+intent] ||= crypto.randomUUID();   // same key, same *intent*
// retry-on-timeout reuses the key; new user intent = new key; cold-start: persist to disk for checkout.
```

### A.4 Outbox tuning invariants (HR-11)
```yaml
app:
  outbox:
    poll-interval: 5s
    batch-size: 30               # drain rate = batch / interval per pod; scale for backlog math: W-9-2
    processing-timeout: 60s     # MUST exceed batch-size × send-timeout + jitter with margin — startup check enforces
    send-timeout: 5s
```

### A.5 Pod hardening canonical (all Deployments; kubeconftest-enforced)
```yaml
securityContext: { runAsNonRoot: true, runAsUser: 10001, seccompProfile: { type: RuntimeDefault } }
containers:
  - securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ["ALL"] } }
    env: [{ name: HOME, value: /tmp }, { name: JAVA_TOOL_OPTIONS, value: "-Djava.io.tmpdir=/tmp" }]
    volumeMounts: [{ name: tmp, mountPath: /tmp }]
volumes: [{ name: tmp, emptyDir: {} }]
topologySpreadConstraints: [{ maxSkew: 1, topologyKey: kubernetes.io/hostname,
   whenUnsatisfiable: ScheduleAnyway, labelSelector: { matchLabels: { app: order } } }]
```

### A.6 PgBouncer (only if W-3.3=B)
```ini
pool_mode = transaction
default_pool_size = 40          # server conns per DB, budgeted under primary max_connections
max_client_conn = 2000
query_wait_timeout = 30
server_idle_timeout = 600
# app JDBC unchanged except the host; NOTE: no session SET persistence, LISTEN/NOTIFY breaks, prepared-statement
# mode required; Flyway + Hibernate quirks reviewed; HPA-on-pooler NOT applicable (corrected claim)
```

### A.7 Verification toolbox (D-15)
```bash
kubectl run chk --rm -i --restart=Never --image=curlimages/curl:8.10.1 -n bhukkad -- -fsS http://bhukkad-order:8092/health/detailed | jq
kubectl run psql --rm -i --restart=Never --image=postgres:16-alpine -n bhukkad -- psql "$(kubectl get cm bhukkad-config -n bhukkad -ojsonpath='{.data.DB_URL}')" -Atc 'select 1'
kubectl get deploy -n bhukkad -ojson | jq '.items[].spec.template.spec | select(.topologySpreadConstraints==null) | {c:.containers[0].name}' # = []
```

---

## Appendix B — Monitoring & alert-rule registry (instrument-first)

Ban rule (D-14): alert merge requires metric export PR + scrape proof attached (`/actuator/prometheus` sample line). **Verified series audit (R4) — what exists vs what the rules query:**
   - **Live and matching:** `bhukkad_outbox_pending{service}` (OutboxMetrics = gauge; earlier `_total` suffix in this doc's drafts was wrong) ← rule `:28` ✓; `bhukkad_outbox_dead_letter` ← rules `:21-31` ✓ (code `OutboxMetrics.java:35-40`); relay "events dead-lettered" counter feeds same alert; `hikaricp_connections_pending/timeout_total` ← rules `:64-79` ✓ (Boot/Micrometer default binders — verify each service keeps them after Spring Boot upgrades); `bhukkad_health_available{service}` (HealthController `:145` gauge); `bhukkad_consumer_dlt_records_total` exported by consumers (`ConsumerRetrySupport`-family) though the DLT *alert* (`:43`) rides `kafka_consumergroup_lag` (redpanda/kafka-exporter component) — both valid instruments; backup rules mix `kube_job/kube_cronjob` states + `backup_last_success_epoch_seconds` + `restore_drill_last_success_timestamp_seconds` (pushers: cronjob + `scripts/ci/restore-drill.sh`).
   - **Never-firing as shipped (fix in W-6.1/W-5.4):** rule `:88` queries `resilience4j_circuitbreaker_state{name,state="open"}` but the filter exports plain **`circuit_breaker_open{name}`/`circuit_breaker_state{name}`** gauges (`CircuitBreakerFilter.java:54-79`, static `SHARED_REGISTRY` = no R4J micrometer binder despite `PlatformWebClientBuilderFactory.java:136-138` passing the registry through) — repoint that one expression; SSE capacity rules (`:127,140`) query `sse_capacity_rejected_total{reason=global|dispatch}` which **realtime/delivery export (their javadoc metric contract) but ORDER's own registry exports nothing** — wire order (`sse_connections` counter) and add `sse_capacity_rejected_total{reason="order-registry"|"stream"}` before trusting the alert; `bhukkad_events_external_enabled` (backbone gauge) never existed — drop or backport into `EventBackbonePreflight`.
   - **Additions with owners:** `idempotency_backlog_recs/_max_age_seconds` (W-7), `idempotency_key_missing_total{scope}` (W-7), `auth_lockout_active{outcome="failopen"}` (W-1.3 — the counter already exists, only the outcome value is new per LoginLockout's existing metric list), `pg_replication_slot_wal_retained_bytes`/lag seconds (W-3-A + exporter), `bhukkad_gateway_pool_active/pending{route}` (after W-5.4 pool config + `metrics=true`), **nginx connection/zone counters** (prometheus-nginx-exporter sidecar — W-5.1: without them the ingress-layer 429s and the worker_connections budget are invisible), `kafka_consumer_lag` (already via rules `:53`).
   - JWKS rules: `bhukkad_jwks_fetch_failures`-style names exist in identity's fetcher (`JwtJwksFetcher`/`JwtSecretRotationService` counters) — confirm exact names when the JWKS cut (W-8.2 step 4) lands.

## Appendix C — Verification & go-live checklist (final)
- [ ] W-1: admin fail-fast; probe drill; failopen metric.
- [ ] W-2: listener stop/start drill with flag flip.
- [ ] W-3: chosen design proven (`pg_stat_replication`/operator status); restore current to new PITR path; naming/pooler decision recorded.
- [ ] W-4: ≤2 q/page proof + EXPLAIN diffs.
- [ ] W-5: ingress classes live; TLS chain monitored; actuator netpol/policy applied; 1k-stream soak.
- [ ] W-6: 5xx-breaker test, jitter spread test, 2-pod lock race, spread node-drill; Redis design executed.
- [ ] W-7: idempotency ITs; synthetic staging loop green 72 h.
- [ ] W-8: rotation drill zero-downtime; header golden tests; secrets audit closed; audit topic flowing.
- [ ] W-9: waves w/ soak reports; lag≈0; DLQ≈0.
- [ ] W-10: contract diffs zero non-approved; Jackson flag retired.
- [ ] W-11: digest-pinned prod deploy verifiable (`kubectl get deploy -o json` vs CI digests); 12-svc smoke; nightly non-vacuous; D-docs landed; D-16 bot first run.

## Appendix D — Rollout windows & communication plan
Per wave W-3→W-9 windows: announce T-2; freeze shared-infra deploys during drain; client coordination: `require`-mode flips only after adoption-metric > 99 % (two client releases); cert-manager: pre-install cert readiness (H-15) not midnight; JWKS fleet: verify old-key path active before rotation; every rollback item has named owner + command (per workstream).

## Appendix E — Compliance matrix (kept from v2.1 with W-8 rows)
Secrets→GDPR Art32/PCI3.4; audit→Art30/10.3; minimization→Art5; epoch/refresh→Art32/8.2; health/availability→A.12.3.1/A1.2; container→CIS (v1 §7 citations correct); TLS→4.1; validation/payload→Art25/CC3.1; DSA ledger→Art28.

## Appendix F — Metric/alert catalog (source of truth for B)
Table per Appendix B: metric | code site | scrape proven | alert id | runbook link. (No row without row 3.)

## Appendix G — Implementation risk register — v3.0 final (supersedes all prior)

| ID | v2.x claim | Verdict | Resolution here |
|----|------------|---------|------------------|
| CR-1/2 | sync-commit/RollingUpdate as HA fix | Confirmed regression | W-3 designs A/B; C rejected; policy gate |
| CR-3 | one-line pipeline flip | Confirmed big-bang | W-9 staged |
| CR-4 | dependency-wired probes | Confirmed trap | W-1.2 probe contract |
| CR-5 | interceptor response capture/replay | Confirmed broken | W-7 wrapper + status preservation |
| CR-6 | plain CREATE EXTENSION migration | Confirmed boot-killer | W-4.2 ops-job guard; W-3 image gate |
| CR-7 | Strimzi topic CRDs | Confirmed stack mismatch | W-8-6 via topics-init |
| CR-8 | service-layer method security | Confirmed async deny | W-6 boundary authz |
| CR-9 | "no ingress; CSP nowhere" | **WRONG (corrected): ingress exists** — but **no CSP annotated**, service-direct bypass keeps platform-lib authoritative | W-5 edge + W-8 headers rewrite |
| CR-10 | Jackson swap safe | **WRONG premise**: payload shapes are live contracts | W-10.1 fixtures + dual-read |
| CR-11 | validation = free | error envelope differs | W-10.0 advice-first |
| CR-12 / HR-12 | "pgbouncer deployed but unused" | Partly mis-evidenced (**no HPA on pooler exists**) — dead-path claim still true | W-3.3 decide |
| CR-13 | (R3) "ingress limit-rps 30 caps every IP" | **Partly wrong (R4):** no ingress controller in repo ⇒ that cap is **inert**; the live caps are nginx zones (`api_general 30 r/s`, `api_order 10 r/s` incl. SSE-connect, `limit_conn 20/IP`-stream) — same CGNAT problem class, correct object | W-5.1 sizing ADR |
| CR-14 | (R3) "consumers not start/stopable" | Confirmed **doubly**: `@EnableKafka`+factories+relay all share one expression ⇒ flip = big-bang (W-2 redesign = property gate + registry) | W-2 |
| CR-15 | (R3) preflight refuses disabled-backbone prod boot | Repo state self-contradictory (`SPRING_PROFILES_ACTIVE=prod` + `ENABLED=false` in the same envFrom); reconcile vs live cluster first | W-2.1 truth step |
| CR-16 | (R4) live edge = `k8s/nginx`; **upstream points at ghost Service `bhukkad-app`** | Fresh GitOps install = all-traffic 502; ghost name also poisons the HPA overlay | ✅ FIXED 2026-09-11 — upstream → `bhukkad-gateway:8080` (base kustomize renders) |
| CR-17 | (R4) internal TLS already ships, commented out | `tls-internal` (CA + pg/redis/redpanda/nginx Certs) behind `kustomization:146`; public trust undecided | W-5.2 / W-3.5 |
| CR-18 | (R4) custom-metrics HPA overlay targets ghost `bhukkad-app` + metrics `sse_active_connections`/…counters **nowhere exported** (truth: `sse_connections` realtime/delivery; order: none) | Overlay never scales anything; silent false confidence | ✅ FIXED 2026-09-11 — overlay retargeted to `bhukkad-order`, replaced-base semantics kept (shared name `bhukkad-order-hpa`), only-standard metrics (CPU+`http_server_requests_seconds_count` pending adapter rule); breaker `CircuitBreakerOpen` + `GatewayUnmatchedRoutesSustained` exprs repointed to exported series. Order-side `sse_connections` export = still open (W-5.4) |
| CR-19 | (R4) Hikari budget **per pod**: 14×3×20 ≈ 840 vs `max_connections=500` (×2 at routing) | Connect storms at scale = 10 s/3 s `connection-timeout` request failures; PgBouncer decision forced | W-5.6→W-3.3 |
| H-14 | (R4) backup reads `S3_BUCKET` from ConfigMap (`CHANGE_ME_…`, live key) | Daily backups → nonexistent bucket; `.last_success_epoch` masking; HPA on pooler claim corrected | W-3.4 |
| HR-1 | paged JOIN FETCH | Confirmed HHH000104 | W-4.1 two-step |
| HR-2 | Bloom revocation | FP direction wrong | W-8.2 sets+cache |
| HR-3 | preload now | irreversible w/ unproven cert chain | W-5.2 first |
| HR-4/5/6/7/8/10 | exec-curl, synthetic-prod, silent DTO swaps, replica both-wrong, AdminClient-per-request, breaker status-blind RetryFilter-blame | All confirmed; HR-10 nuance: *RetryFilter does handle 5xx*, breaker status-blind + **custom-gauge-not-resilience4j-metric** | W-7/8 fixes; W-6.1 |
| HR-9 | "5xx treated as success" (RetryFilter) | Half-true: filter retries it **only when an exception classifies it**; response-status path is the breaker's gap | W-6.1 |
| HR-13/14/15/16/17/18 | JWKS mixed fleet, backup S3 source, epoch rollout order, `.bak` compile, sentinel sed fragility, shedlock coverage | Confirmed (provider matrix resolved: 3 have it, rest run unlocked → W-6.3) |
| HR-11 | relay timing | processingTimeout > batch drain invariant | A.4 + startup check |
| M-1..M-13 | breaker metric name mismatch, cleanup backlog math, fail-open visibility, lock reset on failover, in-tx saga RPCs (M-14 new), wal_keep vs max_wal (M-11), wrapper memory (M-13) | Confirmed | W-1.3/6.1 + notes |

**Residual/unknowns (deliberate):** ingress TLS prerequisite cluster state; per-service factory autoStartup binding (W-2.1 resolves); PgBouncer-vs-direct performance deltas (measure post-choose); CloudNativePG migration rehearsal length; JWKS legacy-fleet inventory count.

---

*End of Document*
