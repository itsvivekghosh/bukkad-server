# Zero-Downtime Schema Migrations (Feature #11)

MySQL DDL is not transactional: `ALTER TABLE` on large tables (orders, outbox_events,
fraud_events) can lock the table for minutes and stall production traffic. This document
defines the project's zero-downtime migration workflow.

## When this applies

- Any `ALTER TABLE` on a table with > 1M rows or with live write traffic.
- Adding NOT NULL columns to hot tables (full table rebuild).
- Adding indexes to hot tables (can use `ALGORITHM=INPLACE` for most, but
  `idx_*` on 10M+ rows still blocks on the final metadata lock).

## Expand-contract pattern (default)

1. **Expand** — migration `V{n}` adds the new column as nullable (or a new table),
   non-breaking for the currently deployed app.
2. **Deploy** — rollout the app that reads/writes both old and new state
   (feature-flagged write path).
3. **Backfill** — a background job (or `UPDATE ... WHERE new_col IS NULL` in batches)
   fills the column.
4. **Contract** — migration `V{n+2}` adds NOT NULL / unique constraint / drops the
   old column, then the next deploy stops reading the old state.

New migrations in this repo should follow the idempotent style of `V31__add_totp_mfa.sql`
(information_schema guards + prepared statements) so partial reruns are safe.

## Online DDL tools (for very large tables)

Prefer MySQL 8 native online DDL where possible:

```sql
ALTER TABLE orders
    ADD COLUMN new_col VARCHAR(50) NULL,
    ALGORITHM=INPLACE,
    LOCK=NONE;
```

For index-only changes on the largest tables:

- **pt-online-schema-change** (Percona Toolkit):
  `pt-online-schema-change --alter "ADD INDEX idx_x (col)" D=bhukkad,t=orders --execute`
- **gh-ost** (GitHub):
  `gh-ost --host=... --database=bhukkad --table=orders --alter="ADD INDEX idx_x (col)" --execute`

Both create a shadow table and swap it in via rename, so the original table is never
locked for the whole operation. Run these manually with a maintenance window; do not
put them in a Flyway migration.

## Flyway configuration

- `spring.flyway.baseline-on-migrate: true` — already set.
- MySQL ignores transactional DDL; do not rely on Flyway wrapping migrations in a
  transaction. Keep each migration idempotent (see V31 style).
- `ignore-migration-patterns: "*:missing"` — already set, allows squashed history.
- In staging/prod, `FlywayConfig` runs `repair()` before `migrate()` to recover from
  interrupted runs.

## Verification

After any schema change, run:

```bash
mysql -h $DB_HOST -e "SELECT TABLE_NAME, TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='bhukkad' AND TABLE_ROWS > 1000000;"
# Review new queries with EXPLAIN before promoting to production
```
