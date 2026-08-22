# Database Migrations

Flyway-managed schema in `src/main/resources/db/migration`.

## Migration set

| Version | File | Purpose |
|---------|------|---------|
| V1 | `V1__baseline_schema.sql` | Consolidated baseline schema (merged from the original V1–V26 series) |
| V2 | `V2__platform_operations.sql` | Platform/operations tables: `city_configs`, `fraud_review_queue`, `disputes`, `affiliate_codes`, `affiliate_referrals`, `tenants` + `restaurants`/`promotion_campaigns` columns |
| V27 | `V27__api_keys.sql` | Partner API key management (`api_keys`) |
| V28 | `V28__missing_entity_tables.sql` | Entity/summary tables dropped during the V1 consolidation: `cities`, `group_orders`, `group_order_participants`, `dead_letter_events`, `restaurant_ratings_summary`, `restaurant_order_stats` |

## Fresh database

Flyway applies V1 → V2 → V27 → V28 in order on first startup. Every statement
is idempotent (`IF NOT EXISTS` / information_schema guards), so re-runs are safe.

## Existing staging / production databases

Databases that were migrated before the V1 consolidation carry Flyway history
for the original V2–V26 series. The app is configured with
`ignore-migration-patterns: "*:missing"` so startup succeeds when those files
no longer exist.

**After deploying a change that alters an already-applied migration's
checksum**, run Flyway repair once per environment:

```bash
# Via CI (set FLYWAY_REPAIR_ON_DEPLOY=true) or manually:
bash .github/scripts/ec2.sh flyway-repair <user> <host> <key> \
  src/main/resources/db/migration /tmp/flyway-env.txt
```

## Adding schema changes

Create the **next version** file, e.g. `V29__your_change.sql`.

Rules:

* Do not edit `V1__baseline_schema.sql`, `V2__platform_operations.sql`, or any
  other migration that has already been applied to a shared environment —
  changing a file's content changes its Flyway checksum and requires a repair.
* New migration files must be **idempotent** (`CREATE TABLE IF NOT EXISTS`,
  guarded `ALTER TABLE`) so they are safe on both fresh and existing databases.
* Never commit a migration that creates a table already created by an earlier
  file — duplicate `CREATE TABLE` will fail on fresh databases.
