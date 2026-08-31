# Bhukkad MySQL → PostgreSQL Production Cutover Runbook

**Status:** Ready for rehearsal — requires deployed infrastructure
**Branch:** `migration/microservices-postgresql`
**Target:** Migrate all MySQL data to the per-service PostgreSQL databases and retire MySQL.

This runbook is the operational counterpart of `docs/architecture-microservices-postgresql.md` §12. It sequences **initial load → incremental sync → shadow traffic → validation → cutover → teardown** using pgloader (bulk), Debezium or binary-log change data capture (incremental), and the strangler-fig gateway route.

---

## 0. Preconditions (ALL must pass before step 1)

| Check | Command / Evidence |
|---|---|
| MySQL backup verified | `mysqldump --single-transaction --routines --triggers` completed; restore tested on a scratch DB |
| PostgreSQL healthy | `pg_isready`; `SELECT version()`; autovacuum running |
| Flyway PG baselines validated | `./mvnw -B flyway:validate` per service against PG |
| App PG profile boots | `SPRING_PROFILES_ACTIVE=postgresql` app starts against PG locally |
| Monitoring + alerts active | Prometheus scraping both MySQL and PG app pools; Grafana dashboards live |
| Rollback ready | MySQL app pool still serving; DNS/TrafficManager switch documented |
| Load tests pass | k6 `hot-paths.js` against the PG-backed build |

---

## 1. Initial Load (offline, one-time)

1. Snapshot the source tables in dependency order (children first is safe with `--disable-triggers`).
2. Run **pgloader** per service database:

```bash
# Example: orders service database
pgloader \
  mysql://bhukkad_user:${MYSQL_PASSWORD}@mysql:3306/bhukkad \
  postgresql://bhukkad:${PG_PASSWORD}@pg:5432/bhukkad_orders \
  -f docs/pgloader/orders.load
```

Use per-service `.load` files that SELECT only that service's owned tables (see
`docs/architecture-microservices-postgresql.md` §5 ownership matrix). Never load
another service's tables into your database.

3. For very large tables (`orders`, `orders_archive`, `order_items`) use
   **chunked COPY** instead of a single `INSERT ... SELECT`:

```sql
-- On the PostgreSQL side, per 100k-row chunk
COPY orders FROM PROGRAM 'mysql -h mysql -u bhukkad_user -p... -e "SELECT ... WHERE id > :last AND id <= :last+100000"' WITH (FORMAT csv);
```

4. Record row counts per table in `data-reconciliation.tsv` (below).

### Orders archive partitioning

If `orders_archive` is range-partitioned on PG, load into partitions directly
and attach them afterwards (avoids index rebuild churn):

```sql
CREATE TABLE orders_archive_y2024q1 PARTITION OF orders_archive
  FOR VALUES FROM ('2024-01-01') TO ('2024-04-01');
```

---

## 2. Data Reconciliation (gate — do not proceed on any mismatch)

For every migrated table compare against source:

| Check | Query pattern |
|---|---|
| Row counts | `SELECT count(*)` both sides; diff per table |
| PK set | `SELECT set_agg(id::text)` both sides; symmetric difference must be empty |
| NULL counts | `SELECT count(*) FILTER (WHERE col IS NULL)` both sides |
| Min/max | `SELECT min(created_at), max(created_at)` both sides |
| Monetary totals | `SELECT round(sum(amount)::numeric, 2)` on money columns |
| Timestamps | Spot-check a 1% sample for TZ shift (MySQL DATETIME is naive → verify UTC) |
| JSON integrity | `SELECT count(*) FROM t WHERE payload::jsonb IS NULL` |
| Enum distributions | `SELECT status, count(*) GROUP BY status` both sides |

Script: `scripts/reconcile.sh --source mysql --target pg --tables <list>`
Exit code 0 only when every check passes. **Investigate every mismatch — do not
accept "close enough".**

---

## 3. Incremental Synchronization (CDC)

After the initial load, capture changes since the snapshot point:

1. Enable MySQL binary logging (`binlog_format=ROW`, already on for prod-grade MySQL).
2. Use **Debezium MySQL connector** (or `mysqlbinlog --start-position`) to stream
   changes into the same tables on PG, keyed by PK with **idempotent UPSERT**:

```sql
INSERT INTO orders (...) VALUES (...) ON CONFLICT (id) DO UPDATE SET ...;
```

3. Track lag: `SELECT * FROM pg_stat_replication;` / Debezium lag metric. Cutover
   may start when lag < 5 seconds sustained for 15 minutes.

---

## 4. Shadow Traffic (dual-read)

While the monolith still serves production from MySQL:

1. Deploy the PG-backed app build as a **shadow pool** (no real user traffic).
2. Replay production traffic at 1–5% by copying a live request stream into the
   shadow pool (nginx mirror / Traffic Mirroring).
3. Compare responses byte-for-byte (ignore traceId/timestamp fields). Any
   semantic diff is a bug — fix and re-run before proceeding.
4. Run k6 `hot-paths.js` against the shadow pool; confirm p50/p95/p99 within
   the approved regression threshold vs MySQL (default: p95 +10%).

---

## 5. Controlled Cutover (zero/minimal downtime)

Sequence (expand/contract):

1. **EXPAND**: both MySQL and PG app pools serve traffic. nginx
   `/api/v1/**` routes are dual (round-robin 99:1, then gradually toward PG).
2. Verify PG pool: error rate, p50/p95/p99, DB CPU, connection pool, business
   metrics (orders/hour, revenue) match MySQL baseline.
3. Increase PG share 5% → 10% → 25% → 50% → 100%, holding ≥ 30 min at each
   step while error rate < 0.1% and p95 within threshold.
4. **Switch writes**: flip the gateway/connection string so new writes go to PG
   only. MySQL enters read-only maintenance mode.
5. Verify: smoke tests, health checks, critical API tests (auth, order create,
   payment webhook, delivery proof) all green on PG-only.

### Gateway switch (nginx)

```nginx
upstream bhukkad_app {
    # Post-cutover: only PG-backed app servers
    server app-pg-1:8080 max_fails=3 fail_timeout=30s;
    server app-pg-2:8080 max_fails=3 fail_timeout=30s;
}
```

---

## 6. Post-Cutover Verification (72h window)

Monitor with the per-service Grafana dashboards:

- Request latency p50/p95/p99 per endpoint
- Error rate (4xx/5xx) per endpoint
- DB CPU / connections / lock waits (`pg_stat_activity`, `pg_stat_statements`)
- Top slow queries (`pg_stat_statements` ordered by mean_time)
- Kafka consumer lag, outbox lag (`outbox_events` PENDING count)
- SLO burn-rate alerts (availability 99.9%, p95 < 1s, p99 < 3s)

Business checks: order completion rate, payment success rate, wallet balance
totals, settlement totals match the MySQL side (queried from the read-only
MySQL copy).

---

## 7. Rollback

**Trigger (any of):** error rate > 1% for 10 min, p95 regression > 20% for 30
min, data reconciliation mismatch found post-cutover, critical API failure.

| Layer | Rollback |
|---|---|
| Traffic | Flip nginx upstream back to the MySQL-backed app pool |
| App | Redeploy previous MySQL-app image (docker-deploy.sh / kubectl rollout undo) |
| Config | Restore DB_URL/SPRING_PROFILES_ACTIVE=dev (MySQL) |
| Database | MySQL is still running read-write until the 72h window closes; no restore needed for the first 72h |
| Events | Kafka/outbox consumers continue; any events produced on PG path are idempotent (outbox + idempotency keys) |

After 72h clean window, MySQL is archived (read-only) for 30 days before deletion.

---

## 8. Monolith Teardown (after every service cut over)

1. Point nginx routes for each `/api/v1/<service>/**` prefix at the extracted
   service (already the case per `services/docker/nginx/nginx.conf`).
2. Remove the corresponding monolith controller/service classes only after the
   service's contract tests + shadow traffic prove parity.
3. Drop the monolith from the build reactor; keep only `services/pom.xml`.
4. Delete MySQL resources (RDS instance / compose service / PVC) after the
   30-day archive window.

---

## 9. Rehearsal

Before production: run steps 1–6 against staging with a production-sized sample
(≥ 10% of expected rows, ≥ 1 day of write volume). Record and keep:

- Migration duration, throughput (rows/s), CPU/memory of both DBs
- Validation time and any mismatches found
- Cutover downtime (must be < 30s)
- Rollback time (must be < 5 min)
