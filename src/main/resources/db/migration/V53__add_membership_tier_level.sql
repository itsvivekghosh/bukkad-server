-- V53__add_membership_tier_level.sql
-- Adds the tier_level column to membership_plans that the MembershipPlan
-- entity maps but no earlier migration created. Without this, Hibernate's
-- SELECT includes tier_level and queries against the table fail with an
-- "Unknown column" SQL error (visible as a 500 on /home/feed and /mobile/feed
-- in environments whose DB was built purely from Flyway migrations).

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'membership_plans'
       AND COLUMN_NAME = 'tier_level') = 0,
    'ALTER TABLE membership_plans ADD COLUMN tier_level INT NOT NULL DEFAULT 0 COMMENT ''0=Basic, 1=Silver, 2=Gold, 3=Platinum'' AFTER discount_percent',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
