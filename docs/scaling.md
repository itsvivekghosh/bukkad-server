# Horizontal Scaling — Monolith Phase (Phase 1)

This document captures how the Bhukkad monolith is scaled horizontally and the
concrete knobs to tune. It is the operational companion to the deployment
manifests (`docker/`, `k8s/`) and the load-test scenarios (`loadtest/`).

## 1. Statelessness guarantees

Every instance is a stateless JVM; session/counter/flag state lives in Redis.
The audit (Phase 1) fixed every remaining in-memory state:

| Component | Before | After |
|---|---|---|
| `FeatureFlagService.runtimeOverrides` | local map — kill-switch diverged per replica | Redis hash + pub/sub cache invalidation (`bhukkad:feature-flag:overrides`) |
| `BNPLStrategy.pendingBalances` | local map — credit limit breakable across replicas | Redis counters, atomic Lua claim |
| `AutoRefundService.processedRefunds` | local map — double-refund risk | Redis SETNX idempotency claim |
| `DunningService.retry*` maps | local — retries lost/duplicated in cluster | Redis hashes + ShedLock on the scheduler |
| `AlertService.recentAlerts` | local — duplicate alerts per replica | Redis SETNX dedup (local fallback) |
| `SloMonitorService` | read wrong meter name (`bhukkad.http.request`) | reads `bhukkad.http.requests` + `bhukkad.http.errors` |
| `RiderLocationTrackingService` | broadcast bypassed the Redis relay | publishes through `OrderLiveUpdateBroadcaster` relay |
| `SseEventSubscriber` | dead code, unregistered listener | removed |

Rate limiting was already Redis-backed (Lua `INCR` + `EXPIRE`); SSE delivery was
already cluster-safe via the Redis relay + replay store (verified:
`OrderLiveRedisSubscriber` + `OrderLiveReplayStore`, `Last-Event-ID` replay).

## 2. Replica deployment

### nginx (docker)
`docker/nginx/nginx.conf` upstream now round-robins across `app`, `app2`,
`app3`. Scale with the compose overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml \
               -f docker-compose.scale.yml up -d --scale app=3
```

No sticky sessions: SSE clients reconnect to any replica and recover missed
events from the Redis replay store via `Last-Event-ID`.

### Kubernetes
`k8s/app/deployment.yaml` (2 replicas, rolling update), `k8s/app/hpa.yaml`
(CPU 65% / memory 75%, min 2 max 10). For P99-latency-based autoscaling apply
the custom-metrics overlay (requires Prometheus Adapter):

```bash
kubectl apply -k k8s/overlays/custom-metrics-hpa
```

The overlay adds `http_server_requests_seconds_count_orders` (avg 30) and
`sse_active_connections` (avg 200) Pod metrics to the HPA.

### AWS EC2 Auto Scaling Group policy
Keyed on **CPU + P99 latency + queue depth** (order-service saturation is the
signal that admin/search traffic is being starved):

- **Scale-out (add instance)** when ANY of:
  - CPUUtilization ≥ 70% for 5 min (average over 1 min)
  - p99 latency (CloudWatch: `bhukkad_http_requests_seconds` p99) ≥ 1000 ms for 3 min
  - App queue depth (order-async executor) ≥ 80% of capacity for 2 min
- **Scale-in (remove instance)** only when ALL of:
  - CPU < 30% for 15 min, p99 < 300 ms for 15 min
- **Cooldown**: 300 s after scale-out; 600 s after scale-in.
- **Min/Max**: 2 / 10 (matches the k8s HPA).
- **Capacity buffer**: keep 1 spare instance during prime-time windows.

## 3. Database scaling

- **Read replicas**: `app.datasource.read-replica.replicas[].url` — one
  HikariCP pool per replica, connections round-robined via
  `ReadReplicaSelector`. Add a second replica by extending the list.
- **`@UseReadReplica` coverage** on hot reads: home feed
  (`HomeFeedCacheService`), trending (`TrendingDishService`), menu
  (`MenuServiceImpl`), search (`SearchServiceImpl`), restaurant list
  (`RestaurantServiceImpl`), admin dashboards (`AdminServiceImpl`).
- **HikariCP sizing**: `maximum-pool-size = cores × 2 + 2` per replica.
  Defaults: primary prod pool 50 (24 cores), replica pool 10 (4 cores) —
  override with `DB_POOL_SIZE` / `DB_REPLICA_POOL_SIZE`.
- **MySQL**: `max_connections` must be ≥ Σ(primary pool + replica pools ×
  replicas) + admin/backup headroom. `docker/mysql/my.cnf` sets 200; raise with
  the app pool sizes.

## 4. Per-replica SLO monitoring

Each replica runs its own `SloMonitorService` window (per-replica p95 +
error-budget burn rate), and `AlertService` dedups alerts cluster-wide via
Redis, so N replicas do not produce N alerts. Expose per-replica metrics via
`/actuator/prometheus`; the `monitoring/grafana/bhukkad-slo-sli.json`
dashboard groups by `application` instance tag.

## 5. CDN for menu images

The S3 → CloudFront wiring is now active in `MenuImageService.resolvePublicUrl`:

- Configure `app.storage.s3.cloudfront.domain` (e.g. `d2abc3xyz.cloudfront.net`).
- The service returns `https://{domain}/{key}` for stored menu-image keys,
  offloading image traffic from the API replicas.
- Distribution settings: HTTP/2 enabled, gzip enabled, origin = S3 bucket
  `bhukkad-menu-images` with **Origin Access Control (OAC)** — never make the
  bucket public. Cache policy: `CachingOptimized` with a 1-day TTL for
  `menu-items/*`.

## 6. Verifying the scaling claim

Run the hot-paths load test (feed + menu + search + order create concurrently)
against N replicas:

```bash
BASE_URL=http://localhost:8080 k6 run loadtest/hot-paths.js
```

Thresholds prove reads (feed/menu/search p95 < 500 ms) no longer saturate the
order-create write path (p95 < 800 ms) and vice versa. CI: `Load Test`
workflow (`load-test.yml`).
