-- V43__group_orders.sql
-- Adds group ordering and bill splitting tables:
--   1. group_orders        : one row per group order (host + status + timestamps)
--   2. group_order_members : invited/joined users, invite phone, contribution
--
-- V28 already creates a legacy-shaped `group_orders` table. The GroupOrder entity
-- has since been redesigned around host/title/placed_at, so this migration
-- EXPANDS the existing table idempotently (add-column-if-missing) instead of
-- relying on CREATE TABLE IF NOT EXISTS, then adds indexes guarded on column
-- existence. Legacy columns are left in place; JPA simply ignores them.

SET @schema_name = DATABASE();

CREATE TABLE IF NOT EXISTS group_orders (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    host_user_id    BIGINT        NULL,
    title           VARCHAR(100)  NULL,
    status          VARCHAR(30)   NOT NULL DEFAULT 'OPEN',
    created_at      DATETIME(6)   NULL,
    placed_at       DATETIME(6)   NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- host_user_id (nullable here: legacy rows have no host; new writes always set it)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'host_user_id') = 0,
    'ALTER TABLE group_orders ADD COLUMN host_user_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'title') = 0,
    'ALTER TABLE group_orders ADD COLUMN title VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'placed_at') = 0,
    'ALTER TABLE group_orders ADD COLUMN placed_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Index only when the column it targets exists (fresh installs create it above;
-- databases carrying the legacy shape get it via the ALTERs above).
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND COLUMN_NAME = 'host_user_id') > 0
    AND (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders' AND INDEX_NAME = 'idx_group_host') = 0,
    'CREATE INDEX idx_group_host ON group_orders (host_user_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS group_order_members (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    group_order_id       BIGINT        NOT NULL,
    user_id              BIGINT        NOT NULL,
    invite_phone         VARCHAR(15)   NULL,
    status               VARCHAR(20)   NOT NULL DEFAULT 'INVITED',
    amount_contribution  DOUBLE        NULL,
    paid                 TINYINT(1)    NOT NULL DEFAULT 0,
    joined_at            DATETIME(6)   NULL,
    UNIQUE KEY uq_group_member (group_order_id, user_id),
    INDEX idx_group_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_order_members' AND INDEX_NAME = 'idx_group_member_user') = 0,
    'CREATE INDEX idx_group_member_user ON group_order_members (user_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;