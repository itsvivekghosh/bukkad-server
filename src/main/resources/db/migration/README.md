# Database Migrations

Single consolidated migration: **`V1__baseline_schema.sql`**

## Fresh database

Flyway runs `V1__baseline_schema.sql` once on startup. No other migration files exist.

## Existing staging / production databases

If the database already has Flyway history for V2–V26, the app is configured with `ignore-missing-migrations: true` so startup succeeds.

**After deploying this change**, run Flyway repair once per environment to fix the V1 checksum:

```bash
# Via CI (set FLYWAY_REPAIR_ON_DEPLOY=true) or manually:
bash .github/scripts/ec2.sh flyway-repair <user> <host> <key> \
  src/main/resources/db/migration /tmp/flyway-env.txt
```

Or connect to RDS and run:

```sql
-- Only if startup fails with checksum mismatch after deploy
-- Prefer: flyway repair (updates checksum automatically)
```

## Adding schema changes

Create **`V2__your_change.sql`** (next version). Do not edit `V1__baseline_schema.sql` after it has been applied to any shared environment.
