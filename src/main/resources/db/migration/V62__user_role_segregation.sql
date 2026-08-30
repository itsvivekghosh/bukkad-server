-- ============================================================================
-- V62: User role segregation
-- ----------------------------------------------------------------------------
-- Splits the shared `users` auth table into per-role, self-contained tables:
--
--   * users               -> slim identity registry (id, role, active, account
--                            state flags, audit). Remains the FK anchor and the
--                            global id allocator for cross-role references
--                            (device_tokens, gift_cards, affiliate codes...).
--   * admins              -> NEW table: admin credentials + profile. Admins
--                            previously existed only as role='ADMIN' rows with
--                            no dedicated table.
--   * customers           -> absorbs email/password/full_name/phone/profile PII.
--   * restaurant_owners   -> absorbs the same auth + profile columns.
--   * delivery_agents     -> absorbs the same auth + profile columns.
--
-- Column split rationale:
--   * Credentials + PII (email, password, full_name, phone_number,
--     profile_image_url, totp_secret) move to the role tables so customer
--     auth traffic never touches shared rows and per-role unique indexes
--     serve logins without scanning a mixed-role table.
--   * Account-level STATE flags (email_verified, phone_verified,
--     phone_verified_at, profile_completed, totp_enabled, active) stay on the
--     registry: they are role-agnostic and queried cross-role.
--   * users.referrer_id moves to customers.referrer_id (referrals are a
--     customer-only concept; customers.referred_by already models the FK).
--
-- Email/phone uniqueness becomes per-role (standard for role-segregated
-- identity); registration keeps global-uniqueness semantics in code via
-- AccountLookupService.existsAnywhere.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. admins table (self-contained credentials + profile)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admins (
    id BIGINT NOT NULL,
    email VARCHAR(100) NULL,
    password VARCHAR(255) NULL,
    full_name VARCHAR(100) NULL,
    phone_number VARCHAR(15) NULL,
    profile_image_url VARCHAR(500) NULL,
    totp_secret VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_admin_email (email),
    INDEX idx_admin_phone (phone_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO admins (id, email, password, full_name, phone_number, profile_image_url, totp_secret)
SELECT u.id, u.email, u.password, u.full_name, u.phone_number, u.profile_image_url, u.totp_secret
FROM users u
WHERE u.role = 'ADMIN'
  AND NOT EXISTS (SELECT 1 FROM admins a WHERE a.id = u.id);

-- ----------------------------------------------------------------------------
-- 2. Auth + profile columns on role tables (nullable first, backfilled below)
-- ----------------------------------------------------------------------------
SET @schema_name = DATABASE();

-- customers
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'customers' AND COLUMN_NAME = 'email') = 0,
    'ALTER TABLE customers ADD COLUMN email VARCHAR(100) NULL, ADD COLUMN password VARCHAR(255) NULL, ADD COLUMN full_name VARCHAR(100) NULL, ADD COLUMN phone_number VARCHAR(15) NULL, ADD COLUMN profile_image_url VARCHAR(500) NULL, ADD COLUMN totp_secret VARCHAR(255) NULL, ADD COLUMN referrer_id BIGINT NULL',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- restaurant_owners
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurant_owners' AND COLUMN_NAME = 'email') = 0,
    'ALTER TABLE restaurant_owners ADD COLUMN email VARCHAR(100) NULL, ADD COLUMN password VARCHAR(255) NULL, ADD COLUMN full_name VARCHAR(100) NULL, ADD COLUMN phone_number VARCHAR(15) NULL, ADD COLUMN profile_image_url VARCHAR(500) NULL, ADD COLUMN totp_secret VARCHAR(255) NULL',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- delivery_agents
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'delivery_agents' AND COLUMN_NAME = 'email') = 0,
    'ALTER TABLE delivery_agents ADD COLUMN email VARCHAR(100) NULL, ADD COLUMN password VARCHAR(255) NULL, ADD COLUMN full_name VARCHAR(100) NULL, ADD COLUMN phone_number VARCHAR(15) NULL, ADD COLUMN profile_image_url VARCHAR(500) NULL, ADD COLUMN totp_secret VARCHAR(255) NULL',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 3. Backfill credentials/PII from users into the role tables
-- ----------------------------------------------------------------------------
UPDATE customers c JOIN users u ON u.id = c.id
SET c.email = u.email, c.password = u.password, c.full_name = u.full_name,
    c.phone_number = u.phone_number, c.profile_image_url = u.profile_image_url,
    c.totp_secret = u.totp_secret, c.referrer_id = u.referrer_id
WHERE c.email IS NULL AND c.password IS NULL AND c.full_name IS NULL;

UPDATE restaurant_owners o JOIN users u ON u.id = o.id
SET o.email = u.email, o.password = u.password, o.full_name = u.full_name,
    o.phone_number = u.phone_number, o.profile_image_url = u.profile_image_url,
    o.totp_secret = u.totp_secret
WHERE o.email IS NULL AND o.password IS NULL AND o.full_name IS NULL;

UPDATE delivery_agents d JOIN users u ON u.id = d.id
SET d.email = u.email, d.password = u.password, d.full_name = u.full_name,
    d.phone_number = u.phone_number, d.profile_image_url = u.profile_image_url,
    d.totp_secret = u.totp_secret
WHERE d.email IS NULL AND d.password IS NULL AND d.full_name IS NULL;

-- ----------------------------------------------------------------------------
-- 4. Per-role unique indexes on email / phone
-- ----------------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'customers' AND INDEX_NAME = 'idx_customer_email') = 0,
    'CREATE UNIQUE INDEX idx_customer_email ON customers (email)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'customers' AND INDEX_NAME = 'idx_customer_phone') = 0,
    'CREATE INDEX idx_customer_phone ON customers (phone_number)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurant_owners' AND INDEX_NAME = 'idx_owner_email') = 0,
    'CREATE UNIQUE INDEX idx_owner_email ON restaurant_owners (email)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurant_owners' AND INDEX_NAME = 'idx_owner_phone') = 0,
    'CREATE INDEX idx_owner_phone ON restaurant_owners (phone_number)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'delivery_agents' AND INDEX_NAME = 'idx_agent_email') = 0,
    'CREATE UNIQUE INDEX idx_agent_email ON delivery_agents (email)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'delivery_agents' AND INDEX_NAME = 'idx_agent_phone') = 0,
    'CREATE INDEX idx_agent_phone ON delivery_agents (phone_number)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 5. Drop PII/credential columns and their indexes from users (registry trim)
-- ----------------------------------------------------------------------------
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_user_email') > 0,
    'DROP INDEX idx_user_email ON users', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_user_phone') > 0,
    'DROP INDEX idx_user_phone ON users', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_user_email_active') > 0,
    'DROP INDEX idx_user_email_active ON users', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_user_role_active') > 0,
    'DROP INDEX idx_user_role_active ON users', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- The referrer FK must be dropped before its column: MySQL error 1828
-- ("Cannot drop column ... needed in a foreign key constraint") otherwise.
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users'
        AND CONSTRAINT_NAME = 'fk_user_referrer' AND CONSTRAINT_TYPE = 'FOREIGN KEY') > 0,
    'ALTER TABLE users DROP FOREIGN KEY fk_user_referrer', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'email') > 0,
    'ALTER TABLE users DROP COLUMN email, DROP COLUMN password, DROP COLUMN full_name, DROP COLUMN phone_number, DROP COLUMN profile_image_url, DROP COLUMN totp_secret, DROP COLUMN referrer_id',
    'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Registry keeps: id, role, active, email_verified, phone_verified,
-- phone_verified_at, profile_completed, totp_enabled, created_at, updated_at.
-- Registry lookup index for role-scoped admin listings.
SET @ddl = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_user_role') = 0,
    'CREATE INDEX idx_user_role ON users (role)', 'SELECT 1'));
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
