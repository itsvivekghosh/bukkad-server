-- V22: Fix missing columns for User and MembershipPlan entities
--
-- The columns were already added manually during earlier debugging.
-- This version is intentionally a no-op so Flyway can advance the schema
-- history without attempting to recreate existing columns.

-- ALTER TABLE users ADD COLUMN referrer_id BIGINT NULL;
-- ALTER TABLE membership_plans ADD COLUMN max_discount_percent DOUBLE NOT NULL DEFAULT 0.0;
-- ALTER TABLE membership_plans ADD COLUMN referral_bonus_percent DOUBLE NOT NULL DEFAULT 0.0;
-- ALTER TABLE membership_plans ADD COLUMN referral_max_per_month INT NOT NULL DEFAULT 0;
