# Chaos Triage Runbook

**Scope:** Chaos experiments run against **staging only** (never production) as part of
the pre-deploy SLO gate (`.github/workflows/chaos-validation.yml`) and the monthly
game day. Each experiment carries a blast-radius label
(`bhukkad.blast-radius`) and a max duration (`bhukkad.max-duration`) and is rolled
back automatically (deleted) after the gate — plus each Chaos Mesh experiment
self-recovers after its `duration` + `deadline`.

## Experiments covered

| Experiment | Type | Blast radius | Max duration | Chaos Mesh kind |
|---|---|---|---|---|
| `pod-kill-gateway` | PodChaos (pod-kill) | single gateway pod | 5m | `k8s/chaos/pod-kill-gateway.yaml` |
| `redis-latency-100ms` | NetworkChaos (delay) | single cache node | 10m | `k8s/chaos/redis-latency-100ms.yaml` |
| `db-connection-exhaustion` | NetworkChaos (delay) | single DB primary | 10m | `k8s/chaos/db-connection-exhaustion.yaml` |

---

## Common first response (all experiments)

1. **Confirm the target environment is staging.** If the experiment is running
   against anything other than `namespace: bhukkad` on the staging cluster,
   stop immediately — this is a production-safety violation.

2. **Check experiment status:**
   ```bash
   kubectl get podchaos,networkchaos -n bhukkad -l component=chaos
   kubectl describe <kind>/<name> -n bhukkad
   ```

3. **Check the blast radius is as expected** (`bhukkad.blast-radius` label) —
   confirm only a single pod/node/primary is affected, not a full deployment.

4. **Observe impact:** error rate, latency, circuit-breaker state, connection-pool
   usage. Baseline metrics:
   ```bash
   # error rate over 1m
   curl -s "$PROM/api/v1/query?query=rate(http_server_requests_seconds_count{job=~\"bhukkad-.*\",status=~\"5..\"}[1m])"
   # p99 latency over 1m
   curl -s "$PROM/api/v1/query?query=histogram_quantile(0.99,rate(http_request_duration_seconds_bucket[1m]))"
   ```

5. **Roll back (automatic + manual):**
   ```bash
   kubectl delete -f k8s/chaos/<experiment>.yaml -n bhukkad --ignore-not-found=true
   ```
   Each experiment also self-recovers after its `duration`; `deadline` bounds how
   long Chaos Mesh retries a missed run.

6. **Record findings** in `docs/chaos-results/game-day-<YYYY-MM>.md` (game day) or
   comment the PR (pre-deploy gate).

---

## 1. pod-kill-gateway

**Scenario:** One bhukkad-gateway pod is killed for 30s. Validates circuit-breaker
fail-over, request hedging, and edge resilience when a gateway instance is lost.

### Symptoms
- Gateway pod `Terminating`/`CrashLoopBackOff` in `bhukkad` namespace
- Brief elevation in 5xx/503 on `/api/v1/health/ping` through the gateway
- Remaining gateway pods absorb traffic; circuit breakers may trip/open briefly

### Immediate actions
1. Confirm only **one** gateway pod was targeted (`mode: one`).
   ```bash
   kubectl get pods -l app=bhukkad,component=gateway -n bhukkad
   ```
2. Verify remaining gateway pods are Ready and serving:
   ```bash
   kubectl rollout status deployment/bhukkad-gateway -n bhukkad
   curl -f http://<gateway>/actuator/health/readiness
   ```
3. Check circuit-breaker state returns to CLOSED after the pod is Ready:
   ```bash
   curl -s "$PROM/api/v1/query?query=resilience4j_circuitbreaker_state{name=~\"gateway.*\"}"
   ```
   (`0` = CLOSED)

### Root causes / failure modes
- **Pod not rescheduled:** Insufficient node capacity / HPA not scaling → scale out nodes or increase HPA maxReplicas.
- **Circuit breaker stays open:** Wait `waitDurationInOpenState` (default 30s); if still open, check upstream health indicators.
- **5xx spike > 1% during the kill:** indicates the canary can't absorb single-pod loss — block promotion, investigate HPA + readiness probe.

### Rollback
```bash
kubectl delete -f k8s/chaos/pod-kill-gateway.yaml -n bhukkad --ignore-not-found=true
```
The killed pod is recreated by the Deployment controller within `duration` (30s).

---

## 2. redis-latency-100ms

**Scenario:** 100ms network latency injected into the Redis cache for 2 minutes.
Validates cache fallback to DB, circuit-breaker behavior, and graceful degradation
when the cache responds slowly.

### Symptoms
- Increased p95/p99 latency on cache-backed endpoints (gateway `/api/v1/**`)
- Edge cache miss rate rises; downstream services see increased DB load
- `ThresholdedRedisHealthIndicator` may report degraded (not OUT_OF_SERVICE — Redis is slow, not down)

### Immediate actions
1. Confirm only a single cache node is affected (`bhukkad.blast-radius=single-cache-node`).
2. Check Redis health and latency:
   ```bash
   kubectl exec -it <redis-pod> -n bhukkad -- redis-cli --latency-history
   kubectl get pods -l app=redis -n bhukkad
   ```
3. Verify the system falls back to DB / serves stale cache instead of erroring:
   ```bash
   curl -f http://<gateway>/api/v1/health/ping
   curl -s "$PROM/api/v1/query?query=rate(http_server_requests_seconds_count{job=~\"bhukkad-.*\",status=~\"5..\"}[1m])"
   ```
4. Confirm latency injected == 100ms (Chaos Mesh `delay.latency`) and recovers after `duration` (2m).

### Root causes / failure modes
- **No fallback → errors spike:** circuit breaker not tuned for slow Redis; cache-aside not configured → lower `slowCallDurationThreshold` / enable cache fallback.
- **Thundering herd on DB:** enable cache warmup + negative caching + stampede guard (see Section 4.4 of the audit).

### Rollback
```bash
kubectl delete -f k8s/chaos/redis-latency-100ms.yaml -n bhukkad --ignore-not-found=true
```
Latency is removed after `duration` (2m); cache read path returns to normal.

---

## 3. db-connection-exhaustion

**Scenario:** PostgreSQL responses stalled (5s delay) for 3 minutes so application
connections pile up and saturate the HikariCP pool. Validates connection-pool
exhaustion handling, retry/backoff, and fail-over to the read replica.

### Symptoms
- HikariCP `active` ≈ `max-active`; `pending`/`queued` requests climb
- Write latency spikes; write requests timeout/fail
- Read path may still succeed via read replica (if replica routing is healthy)
- PgBouncer reconnection / application retry with backoff

### Immediate actions
1. Confirm only the **single DB primary** is affected (`bhukkad.blast-radius=single-db-primary`).
2. Check connection-pool saturation:
   ```bash
   curl -s "$PROM/api/v1/query?query=hikaricp_connections_active / hikaricp_connections_max"
   kubectl top pod -l component=postgres -n bhukkad
   ```
3. Verify read replica failover is working:
   ```bash
   kubectl exec -it <postgres-pod> -n bhukkad -- patronictl list
   curl -f http://<service>/actuator/health/db
   ```
4. Check PgBouncer reconnects and app retries with backoff (no crash-loop):
   ```bash
   kubectl logs -l component=pgbouncer -n bhukkad --tail=50
   ```

### Root causes / failure modes
- **Pool exhausted, no backoff:** app hammers connections → tune `maximum-pool-size`, `connection-timeout`, enable retry/backoff.
- **Read replica not promoted:** Patroni failover stalled → check `patronictl`, replica lag.
- **Writes keep failing after recovery:** pool not draining → restart the affected service rollout.

### Rollback
```bash
kubectl delete -f k8s/chaos/db-connection-exhaustion.yaml -n bhukkad --ignore-not-found=true
```
Injected latency is removed after `duration` (3m); the HikariCP pool drains and
connections to the primary/read replica are re-established.

---

## Gate decision (pre-deploy SLO gate)

The promote/block gate evaluates the **error-budget burn rate over a 5m window**
(after chaos executes and the system reaches steady state):

```
sum(rate(http_server_requests_seconds_count{job=~"bhukkad-(gateway|commerce|catalog|engagement|identity|admin)",status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count{job=~"bhukkad-(gateway|commerce|catalog|engagement|identity|admin)"}[5m]))
```

- **PROMOTE** when burn rate ≤ 1% (≤ 0.01).
- **BLOCK** when burn rate > 1% — do not promote; triage using the experiment-specific
  steps above, record findings in `docs/chaos-results/`, then re-run the gate.

All chaos experiments are rolled back (deleted) after the gate so nothing persists
in staging. See `.github/workflows/chaos-validation.yml` for the exact gate
implementation.
