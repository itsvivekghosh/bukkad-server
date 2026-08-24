-- V31__add_totp_mfa.sql
-- Adds TOTP (RFC 6238) multi-factor authentication support for ADMIN and
-- RESTAURANT_OWNER accounts.
--
--   totp_secret  : base32-encoded TOTP secret; NULL means MFA not enrolled
--   totp_enabled : whether the account requires a second factor at login
--
-- Both columns are added idempotently (information_schema guards + prepared
-- statements) so the migration is safe on databases where they may already
-- exist from a manual hotfix.

SET @schema_name = DATABASE();

-- users.totp_secret
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'totp_secret') = 0,
    'ALTER TABLE users ADD COLUMN totp_secret VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- users.totp_enabled
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'users' AND COLUMN_NAME = 'totp_enabled') = 0,
    'ALTER TABLE users ADD COLUMN totp_enabled TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
