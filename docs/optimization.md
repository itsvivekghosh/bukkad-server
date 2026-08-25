# Scaling & Optimization Practices (Phase 4)

Concrete knobs for ongoing performance work, applied throughout the phases.
Each section lists what is already implemented and what to tune.

## 1. Database

| Knob | Status | Guidance |
|---|---|---|
| Connection pools | Implemented | `maximum-pool-size = cores × 2 + 2` per pool. Primary prod 50, replica 10 (env-tunable `DB_POOL_SIZE` / `DB_REPLICA_POOL_SIZE`). Never exceed MySQL `max_connections` minus admin/backup headroom. |
| Per-domain indexes | Implemented (V52) | `settlement_runs(started_at)`, `coupon_usages(customer_id)`, `reviews(created_at)` already added. Add indexes alongside each new query (EXPLAIN-driven). |
| Cursor pagination | Implemented | `OrderRepository` cursor variants, `CursorUtils`, `PaginationUtils`; `orderSummaryCursor` endpoints. Cursor pagination avoids `OFFSET` scans on hot lists. |
| Batch writes | Implemented | `hibernate.jdbc.batch_size=50`, `order_inserts/updates=true`. Use `StatelessSession` for bulk jobs (archival, settlement runs). |
| Old-order archival | Implemented (V55) | `OrderArchiveService` moves orders > 365 days into the range-partitioned `orders_archive`; oldest partitions are dropped, not bulk-deleted. |
| EXPLAIN review | Practice | Before merging any new query, run `EXPLAIN ANALYZE` and confirm index usage + no filesort on hot paths. |

## 2. Caching

- **Per-domain keyspace prefixes**: `CacheConstants.KEY_PREFIX` (`bhukkad:`), per-domain keys like `bhukkad:restaurant:list:*`, `bhukkad:orders:*`, `bhukkad:search:*`. Keeps cache admins and future per-service keyspaces clean.
- **Cache-aside with TTL jitter** (implemented): `LocalCacheService` ±10% jitter + probabilistic early expiration (stampede protection); Redis `SETNX` lock single-flight across replicas.
- **CDN for images** (implemented Phase 1): S3 → CloudFront in `MenuImageService.resolvePublicUrl`.
- **Phase 3+**: replace per-service Redis pub/sub invalidation with Redpanda-based cache-invalidation events (topic per domain) so services do not share a Redis pub/sub channel graph.

## 3. Async

- **Two-tier executors** (implemented Phase 4): `orderTaskExecutor` (core 4 / max 16 / queue 200, MDC) for order-path work; `lowPriorityTaskExecutor` (core 2 / max 4 / queue 100, MDC) for analytics materialization and notifications. Assign `@Async("lowPriorityTaskExecutor")` to non-urgent fan-out.
- **Redpanda consumer groups**: horizontal consumption per service; tune `max.poll.interval.ms` / `max.poll.records` so a slow consumer does not get kicked from the group.
- **Outbox polling**: `app.outbox.poll-interval-ms=2000`, batch 50; tune per service (order-service 2s, admin-service 30s).

## 4. Observability

- **One traceId across services** (implemented Phase 4): `KafkaPlatformEventPublisher` propagates W3C `traceparent` in Kafka headers; Micrometer tracing (OTel bridge) is enabled per profile.
- **Per-service dashboards**: the `monitoring/grafana/bhukkad-slo-sli.json` dashboard groups by `application`; add per-service panels as modules are extracted.
- **SLO error-budget alerts**: `SloMonitorService` (per-replica p95 + burn rate, Redis-deduped) — metric names fixed in Phase 1 to match `EndpointSloMetrics` (`bhukkad.http.requests` / `bhukkad.http.errors`).

## 5. Resilience

- **Resilience4j** (implemented): circuit breakers `paymentGateway`, `notification*`, `osrm`; retry `paymentGateway` (3 attempts, exponential backoff). Add a bulkhead per downstream call when service extraction increases fan-out.
- **Graceful degradation**: `DegradationProperties` exists; gate each external integration on `app.degradation.features.<name>`. Redis failure paths already degrade (cache fallback, rate-limit fail-open, BNPL fail-closed, tracking fail-closed).
- **Chaos / fault injection**: `com.bhukkad.chaos` provides `@ChaosFaultInjection` (see `ChaosFaultInjectionAspectTest`). Use it in tests for network partitions and timeouts on each new downstream call.

## 6. Security

- **Gateway JWT**: services trust the gateway-issued JWT; internal routes use `ApiKeyFilter` (`X-API-Key`, SHA-256 hashed keys).
- **Service-to-service**: keep API keys for internal REST; consider mTLS when services split (documented in migration runbook).
- **PII**: `LogSanitizer` + `PiiMaskingConverter` mask PII in logs; consent records per customer; data-export retention enforced by `DataRetentionService`.

## 7. Cost / scale guidance

| Service | Scale model |
|---|---|
| order-service | Horizontal (stateless, Redis-backed counters/outbox). Many replicas. |
| payment-service | Stateful, single-writer. Scale vertically + async; never multiple writers. |
| restaurant/menu | Read-heavy; read replicas + CDN; medium replica count. |
| admin/analytics | Never on the hot path. Reads materialized summaries via read replicas; small replica count. |