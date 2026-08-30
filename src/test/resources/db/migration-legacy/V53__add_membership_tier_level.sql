-- V53__add_membership_tier_level.sql
-- Adds columns to membership_plans that the MembershipPlan entity maps but
-- no earlier migration created. Without these columns, Hibernate's SELECT
-- includes them and queries fail with "Unknown column" SQL errors.
--
-- This manifests as a 500 on /home/feed and /mobile/feed in environments
-- whose DB was built purely from Flyway migrations.

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'tier_level') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN tier_level INT NOT NULL DEFAULT 0 COMMENT ''0=Basic, 1=Silver, 2=Gold, 3=Platinum'' AFTER discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'max_discount_percent') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN max_discount_percent DOUBLE NOT NULL DEFAULT 0.0 AFTER discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'referral_bonus_percent') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN referral_bonus_percent DOUBLE NOT NULL DEFAULT 0.0 AFTER max_discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'referral_max_per_month') = 0,
    'ALTER TABLE membership_plans
     ADD COLUMN referral_max_per_month INT NOT NULL DEFAULT 0 AFTER referral_bonus_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;