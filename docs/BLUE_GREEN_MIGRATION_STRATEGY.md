# Blue-Green Database Migration Strategy for Flyway

## Overview
This document defines the strategy for zero-downtime database migrations using Flyway with a blue-green deployment pattern, ensuring backward compatibility and instant rollback capability.

---

## Migration Principles

### 1. **Expand-Contract Pattern** (Mandatory)
Every schema change MUST follow the Expand-Contract pattern:
- **Expand**: Add new columns/tables/indexes (backward compatible)
- **Migrate**: Copy/transform data (can run while old code runs)
- **Contract**: Remove old columns/tables (after new code deployed)

### 2. **Backward Compatibility Rules**
- NEVER drop columns in the same migration that adds them
- NEVER rename columns - add new, copy data, deprecate old
- NEVER change column types directly - add new column, migrate, drop old
- ALWAYS provide defaults for new NOT NULL columns
- ALWAYS make new columns nullable initially

### 3. **Migration Versioning**
- Format: `V{major}_{minor}_{patch}__{description}.sql`
- Example: `V2_1_0__add_customer_tier_column.sql`
- Baseline: `V1_0_0__baseline.sql` (per service)

---

## Blue-Green Deployment Process

### Phase 1: Pre-Migration (Blue Environment Active)
```
┌─────────────────────────────────────────────────────────────┐
│  BLUE (Current Production)                                  │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│  │ Service v1  │    │ Service v1  │    │ Service v1  │      │
│  │             │    │             │    │             │      │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘      │
│         │                  │                  │              │
│         └──────────────────┼──────────────────┘              │
│                            ▼                                 │
│                   ┌─────────────────┐                        │
│                   │  Database v1    │                        │
│                   │  (Schema v1)    │                        │
│                   └─────────────────┘                        │
└─────────────────────────────────────────────────────────────┘
```

### Phase 2: Expand Schema (Backward Compatible)
```sql
-- Migration V2_1_0__add_customer_tier_column.sql
-- EXPAND: Add new column (nullable, with default)
ALTER TABLE customers ADD COLUMN tier VARCHAR(20) DEFAULT 'FREE';

-- Migration V2_1_1__populate_customer_tier.sql
-- MIGRATE: Populate new column from existing data
UPDATE customers SET tier = CASE 
    WHEN total_orders > 100 THEN 'PLATINUM'
    WHEN total_orders > 50 THEN 'GOLD'
    WHEN total_orders > 10 THEN 'PREMIUM'
    ELSE 'FREE'
END;

-- Migration V2_1_2__add_tier_index.sql
-- OPTIMIZE: Add index for new queries
CREATE INDEX idx_customers_tier ON customers(tier);
```

### Phase 3: Deploy Green (New Code Compatible with Both Schemas)
```
┌─────────────────────────────────────────────────────────────┐
│  GREEN (New Version Deployed)                               │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│  │ Service v2  │    │ Service v2  │    │ Service v1  │ ← Canary
│  │ (reads new  │    │ (reads new  │    │ (reads old  │
│  │  column)    │    │  column)    │    │  column)    │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘      │
│         │                  │                  │              │
│         └──────────────────┼──────────────────┘              │
│                            ▼                                 │
│                   ┌─────────────────┐                        │
│                   │  Database v1+   │  ← Expanded Schema     │
│                   │  (Schema v2)    │                        │
│                   └─────────────────┘                        │
└─────────────────────────────────────────────────────────────┘
```

### Phase 4: Contract Schema (After Full Green Rollout)
```sql
-- Migration V2_2_0__remove_old_tier_logic.sql
-- CONTRACT: Remove deprecated logic (after v2 is 100%)
-- Only after confirming zero v1 pods running
-- ALTER TABLE customers DROP COLUMN old_tier_column;
```

### Phase 5: Complete (Green Active)
```
┌─────────────────────────────────────────────────────────────┐
│  GREEN (New Production)                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐      │
│  │ Service v2  │    │ Service v2  │    │ Service v2  │      │
│  └──────┬──────┘    └──────┬──────┘    └──────┬──────┘      │
│         │                  │                  │              │
│         └──────────────────┼──────────────────┘              │
│                            ▼                                 │
│                   ┌─────────────────┐                        │
│                   │  Database v2    │  ← Contracted Schema   │
│                   └─────────────────┘                        │
└─────────────────────────────────────────────────────────────┘
```

---

## Flyway Configuration for Blue-Green

### Application.yml Settings
```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration-pg
    baseline-on-migrate: true
    validate-on-migrate: true
    # Blue-Green specific:
    out-of-order: true           # Allow gap-filling migrations
    ignore-missing-migrations: false
    clean-disabled: true         # Never clean in prod!
    # Callbacks for deployment coordination:
    callbacks:
      - com.bhukkad.common.db.MigrationCallbacks
```

### Migration Callback for Coordination
```java
package com.bhukkad.common.db;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.stereotype.Component;

@Component
public class MigrationCallbacks implements Callback {

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE || event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return false; // Run outside transaction for coordination
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.BEFORE_MIGRATE) {
            // Signal deployment system: migration starting
            DeploymentCoordinator.signalMigrationStart(context.getConfiguration().getTarget());
        } else if (event == Event.AFTER_MIGRATE) {
            // Signal deployment system: migration complete
            DeploymentCoordinator.signalMigrationComplete(context.getConfiguration().getTarget());
        }
    }

    @Override
    public String getCallbackName() {
        return "BlueGreenMigrationCallback";
    }
}
```

---

## CI/CD Pipeline Integration

### GitHub Actions Workflow
```yaml
# .github/workflows/db-migration.yml
name: Database Migration

on:
  workflow_dispatch:
    inputs:
      environment:
        type: choice
        options: [staging, production]
        required: true
      migration_version:
        type: string
        description: "Flyway target version (empty = latest)"
        required: false

jobs:
  migrate:
    runs-on: ubuntu-latest
    environment: ${{ github.event.inputs.environment }}
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Flyway
        uses: flyway/flyway-action@v1
        with:
          version: '10.0.0'
          
      - name: Validate Migrations
        run: |
          flyway -url="${{ secrets.DB_URL }}" \
                 -user="${{ secrets.DB_USER }}" \
                 -password="${{ secrets.DB_PASSWORD }}" \
                 -locations=filesystem:services/identity/src/main/resources/db/migration-pg \
                 validate
          
      - name: Dry Run Migration
        if: github.event.inputs.migration_version != ''
        run: |
          flyway -url="${{ secrets.DB_URL }}" \
                 -target="${{ github.event.inputs.migration_version }}" \
                 migrate -dryRunOutput=/tmp/dryrun.sql
          
      - name: Execute Migration (Blue-Green)
        run: |
          # Step 1: Run Expand migrations only
          flyway -url="${{ secrets.DB_URL }}" \
                 -target="${{ github.event.inputs.migration_version }}_expand" \
                 migrate
          
          # Step 2: Deploy Green service (separate deploy job)
          # Wait for Green health check
          
          # Step 3: Run Contract migrations (after Green 100%)
          flyway -url="${{ secrets.DB_URL }}" \
                 -target="${{ github.event.inputs.migration_version }}_contract" \
                 migrate
```

---

## Migration Checklist (Per Migration)

### Pre-Migration
- [ ] Migration follows Expand-Contract pattern
- [ ] All new columns nullable or have defaults
- [ ] No DROP/RENAME in Expand phase
- [ ] Indexes added CONCURRENTLY (PostgreSQL)
- [ ] Data migration is idempotent
- [ ] Rollback script tested in staging
- [ ] Performance impact assessed (EXPLAIN ANALYZE)

### During Migration
- [ ] Run Expand migrations first
- [ ] Verify schema compatibility with current code
- [ ] Deploy Green service (canary 10%)
- [ ] Monitor error rates, latency
- [ ] Gradually increase Green traffic
- [ ] Run Contract migrations after 100% Green

### Post-Migration
- [ ] Verify all services on new version
- [ ] Run Contract migrations
- [ ] Clean up deprecated code
- [ ] Update documentation
- [ ] Archive old migration scripts (optional)

---

## Emergency Rollback Procedure

### If Green Deployment Fails
```bash
# 1. Route traffic back to Blue (immediate)
kubectl patch service bhukkad-{service} -p '{"spec":{"selector":{"version":"blue"}}}'

# 2. If Expand migrations ran but Contract not run:
#    Database is forward-compatible, Blue continues working
#    No database rollback needed!

# 3. If Contract migrations ran (DANGEROUS):
#    - Restore from pre-migration snapshot
#    - Or run compensating migration (pre-written)
```

### Compensating Migration Template
```sql
-- Migration V2_1_0_compensate__revert_customer_tier.sql
-- COMPENSATING: Revert Expand changes if Contract already ran
-- WARNING: Only run if Green rollback required AND Contract ran

-- 1. Preserve data if needed
CREATE TABLE customers_tier_backup AS 
SELECT id, tier FROM customers;

-- 2. Drop Contract changes (indexes, constraints)
DROP INDEX IF EXISTS idx_customers_tier;

-- 3. Drop Expand column
ALTER TABLE customers DROP COLUMN IF EXISTS tier;

-- 4. Restore old logic if needed
-- (Application code must handle missing column)
```

---

## Migration Categories

### Category 1: Safe (Auto-approved)
- Add nullable column with default
- Add index CONCURRENTLY
- Add check constraint (validated later)
- Create new table (no FK to existing)

### Category 2: Review Required (2 approvers)
- Add NOT NULL column (requires data migration)
- Add foreign key (requires validation)
- Change column type (via new column)
- Partition existing table

### Category 3: High Risk (Architect + DBA approval)
- Drop column/table
- Rename column/table
- Change primary key
- Modify enum values
- Large data migration (> 1M rows)

---

## Monitoring During Migration

### Key Metrics to Watch
| Metric | Normal | Alert Threshold |
|--------|--------|-----------------|
| Migration duration | < 5 min | > 30 min |
| Migration errors | 0 | > 0 |
| App error rate | < 0.1% | > 1% |
| p99 latency | baseline | > 2x baseline |
| DB connections | baseline | > 80% pool |

### Queries for Monitoring
```sql
-- Check migration status
SELECT * FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 10;

-- Check long-running migrations
SELECT pid, now() - pg_stat_activity.query_start AS duration, query
FROM pg_stat_activity
WHERE state = 'active' AND query LIKE '%ALTER TABLE%';

-- Check lock contention
SELECT pid, mode, granted, query
FROM pg_locks
JOIN pg_stat_activity USING (pid)
WHERE NOT granted;
```

---

## Service-Specific Considerations

### Identity Service
- Auth tables: ZERO DOWNTIME REQUIRED
- Use separate migration for user-facing changes
- Token validation must work during migration

### Commerce Service
- Orders table: HIGH CONTENTION
- Use `pg_sleep(0)` in data migrations to yield
- Payment webhooks must not break

### Catalog Service
- Search indexes: Use CONCURRENTLY
- Menu items: Read-heavy, cache invalidation needed

### Engagement Service
- Notifications: Async, eventual consistency OK
- WebSocket connections: Graceful drain

---

## Documentation Requirements

Each migration PR MUST include:
1. **Migration Type**: Expand / Migrate / Contract / Compensating
2. **Category**: 1 / 2 / 3
3. **Backward Compatible**: Yes/No
4. **Rollback Plan**: Link to compensating migration
5. **Performance Impact**: EXPLAIN ANALYZE output
6. **Testing**: Staging validation results
7. **Deployment Order**: Explicit phase (Expand/Migrate/Contract)

---

## Example: Complete Migration Flow

### Scenario: Add `status` column to `orders` table

**Migration 1 (Expand)**:
```sql
-- V3_0_0__add_order_status.sql
ALTER TABLE orders ADD COLUMN status VARCHAR(20) DEFAULT 'PENDING';
CREATE INDEX CONCURRENTLY idx_orders_status ON orders(status);
```

**Migration 2 (Migrate)**:
```sql
-- V3_0_1__populate_order_status.sql
UPDATE orders SET status = CASE
    WHEN delivered_at IS NOT NULL THEN 'DELIVERED'
    WHEN cancelled_at IS NOT NULL THEN 'CANCELLED'
    WHEN driver_assigned_at IS NOT NULL THEN 'IN_TRANSIT'
    WHEN confirmed_at IS NOT NULL THEN 'CONFIRMED'
    ELSE 'PENDING'
END;
```

**Deploy Green** (reads `status` column)

**Migration 3 (Contract)**:
```sql
-- V3_1_0__remove_legacy_status_logic.sql
-- Remove old status computation from views/triggers
DROP VIEW IF EXISTS order_status_view;
-- Application now uses orders.status directly
```

---

## Appendix: Flyway Commands Reference

```bash
# Validate
flyway -url=jdbc:postgresql://host:5432/db -user=user -password=pass validate

# Dry run
flyway -url=... migrate -dryRunOutput=/tmp/dryrun.sql

# Migrate to specific version
flyway -url=... -target=3.0.1 migrate

# Info
flyway -url=... info

# Repair (fix checksums)
flyway -url=... repair

# Baseline existing DB
flyway -url=... baseline

# Clean (DEV ONLY!)
flyway -url=... clean
```

---

## Summary

| Phase | Action | Schema State | Code Versions |
|-------|--------|--------------|---------------|
| 1 | Baseline | v1 | Blue v1 only |
| 2 | Expand | v1 + new cols | Blue v1 |
| 3 | Migrate Data | v1 + data | Blue v1 |
| 4 | Deploy Green | v1 + data | Blue v1 + Green v2 (canary) |
| 4b | Ramp Green | v1 + data | Green v2 (100%) |
| 5 | Contract | v2 | Green v2 only |

**Key Rule**: Database must always be compatible with BOTH old and new code during transition.