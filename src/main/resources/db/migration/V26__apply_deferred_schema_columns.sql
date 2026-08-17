-- V26: Apply schema columns that V20/V22 recorded as no-ops (local-only manual DDL).
-- Idempotent for environments where columns already exist.

SET @schema_name = DATABASE();

-- restaurants.virtual_brand_name
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurants' AND COLUMN_NAME = 'virtual_brand_name') = 0,
    'ALTER TABLE restaurants ADD COLUMN virtual_brand_name VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- users.referrer_id
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'referrer_id') = 0,
    'ALTER TABLE users ADD COLUMN referrer_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- membership_plans columns
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans' AND COLUMN_NAME = 'max_discount_percent') = 0,
    'ALTER TABLE membership_plans ADD COLUMN max_discount_percent DOUBLE NOT NULL DEFAULT 0.0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans' AND COLUMN_NAME = 'referral_bonus_percent') = 0,
    'ALTER TABLE membership_plans ADD COLUMN referral_bonus_percent DOUBLE NOT NULL DEFAULT 0.0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans' AND COLUMN_NAME = 'referral_max_per_month') = 0,
    'ALTER TABLE membership_plans ADD COLUMN referral_max_per_month INT NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
