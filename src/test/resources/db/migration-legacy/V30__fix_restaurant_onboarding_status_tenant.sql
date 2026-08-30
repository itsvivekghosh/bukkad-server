-- V30__fix_restaurant_onboarding_status_tenant.sql
-- Same root cause as V29: on some databases V2 is recorded in
-- flyway_schema_history without the ALTER TABLE taking effect, so the
-- restaurants table is missing onboarding_status and tenant_id. Add both
-- idempotently (information_schema + prepared statements) so every database
-- converges whether or not the columns already exist.

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'restaurants'
      AND COLUMN_NAME = 'onboarding_status'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE restaurants ADD COLUMN onboarding_status VARCHAR(30) NOT NULL DEFAULT ''APPROVED''',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'restaurants'
      AND COLUMN_NAME = 'tenant_id'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE restaurants ADD COLUMN tenant_id BIGINT NULL',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
