-- V29__fix_restaurant_onboarding_rejection_reason.sql
-- Some environments have V2 recorded in flyway_schema_history without the
-- ALTER TABLE actually taking effect, leaving restaurants missing the
-- onboarding_rejection_reason column (app fails with
-- "Unknown column 'r1_0.onboarding_rejection_reason'"). Add it idempotently so
-- every database converges whether or not the column already exists.

SET @col_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'restaurants'
      AND COLUMN_NAME = 'onboarding_rejection_reason'
);

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE restaurants ADD COLUMN onboarding_rejection_reason VARCHAR(255) NULL',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
