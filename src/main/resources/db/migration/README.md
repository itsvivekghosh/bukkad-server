# Database Migrations

Flyway-managed schema in `src/main/resources/db/migration`.

## Migration set

| Version | File | Purpose |
|---------|------|---------|
| V1 | `V1__baseline_schema.sql` | **Squashed baseline**: the entire schema history (original V1–V26 series, standalone V2 platform operations, and V27–V61) concatenated in true chronological execution order |
| V62 | `V62__user_role_segregation.sql` | Per-role account tables: new `admins`; credentials/PII absorbed into `customers` / `restaurant_owners` / `delivery_agents`; `users` trimmed to an identity registry |
| V63 | `V63__order_categorization.sql` | STORED generated `order_category` (LIVE / FULFILLED / CANCELLED) on `orders` + `orders_archive`, with category-leading composite indexes |

## Fresh database

Flyway applies V1 → V62 → V63 in order on first startup. Every statement is
idempotent (`IF NOT EXISTS` / information_schema guards), so re-runs are safe.

## Existing staging / production databases

Databases migrated before the squash carry Flyway history rows for V2 and
V27–V61 whose files no longer exist. The app is configured with
`ignore-migration-patterns: "*:missing"` so the missing files are tolerated.

The squashed `V1__baseline_schema.sql` has a **new checksum**, so run Flyway
repair once per environment after deploying this change:

```bash
# Via CI (set FLYWAY_REPAIR_ON_DEPLOY=true) or manually:
bash .github/scripts/ec2.sh flyway-repair <user> <host> <key> \
  src/main/resources/db/migration /tmp/flyway-env.txt
```

`flyway repair` re-aligns the stored V1 checksum with the new file; V62/V63
then apply normally. No data migration is required — the squash only
concatenates DDL that has already run.

## Baseline integrity

The squashed baseline is verified by
`SchemaEquivalenceIntegrationTest` (Testcontainers MySQL, CI-only): it applies
the pre-squash migration set (snapshot in
`src/test/resources/db/migration-legacy/`) and the squashed set to two fresh
databases and diffs tables, columns and indexes. Do not edit `V1__baseline_schema.sql`
by hand — regenerate the equivalence proof instead.

## Adding schema changes

Create the **next version** file, e.g. `V64__your_change.sql`.

Rules:

* Do not edit any migration that has already been applied to a shared
  environment — changing a file's content changes its Flyway checksum and
  requires a repair.
* New migration files must be **idempotent** (`CREATE TABLE IF NOT EXISTS`,
  guarded `ALTER TABLE`) so they are safe on both fresh and existing databases.
* Never commit a migration that creates a table already created by an earlier
  file — duplicate `CREATE TABLE` will fail on fresh databases.
