-- V42__order_fulfillment.sql
-- Adds order fulfillment support for three features:
--   1. Guest checkout   : anonymous device_id-scoped identity + phone/OTP capture
--   2. Curbside pickup  : orders.fulfillment_type = 'PICKUP' (skip delivery fee + rider)
--   3. Gift orders      : pay now, deliver to recipient, optional gift message
--
-- Every orders column is added idempotently (information_schema guards +
-- prepared statements, V31 style) so the migration is safe on databases where
-- the columns may already exist from a manual hotfix. gift_orders is created
-- with CREATE TABLE IF NOT EXISTS for the same reason.

SET @schema_name = DATABASE();

-- orders.fulfillment_type
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'fulfillment_type') = 0,
    'ALTER TABLE orders ADD COLUMN fulfillment_type VARCHAR(20) NOT NULL DEFAULT ''DELIVERY''',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.device_id
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'device_id') = 0,
    'ALTER TABLE orders ADD COLUMN device_id VARCHAR(64) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.guest_phone
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'guest_phone') = 0,
    'ALTER TABLE orders ADD COLUMN guest_phone VARCHAR(15) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.gift_message
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'gift_message') = 0,
    'ALTER TABLE orders ADD COLUMN gift_message VARCHAR(500) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.recipient_name
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'recipient_name') = 0,
    'ALTER TABLE orders ADD COLUMN recipient_name VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.recipient_phone
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'recipient_phone') = 0,
    'ALTER TABLE orders ADD COLUMN recipient_phone VARCHAR(15) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- gift_orders: one row per gift order, linking the paid order to the recipient
-- details. sender_user_id is the purchasing customer; kept nullable (SET NULL on
-- user deletion) so the gift record survives account removal.
CREATE TABLE IF NOT EXISTS gift_orders (
    id                   BIGINT        AUTO_INCREMENT PRIMARY KEY,
    order_id             BIGINT        NOT NULL UNIQUE,
    sender_user_id       BIGINT        NULL,
    recipient_name       VARCHAR(100)  NULL,
    recipient_phone      VARCHAR(15)   NULL,
    recipient_address_id BIGINT        NULL,
    message              VARCHAR(500)  NULL,
    created_at           DATETIME(6)   NOT NULL,
    CONSTRAINT fk_gift_orders_order  FOREIGN KEY (order_id)        REFERENCES orders(id)  ON DELETE CASCADE,
    CONSTRAINT fk_gift_orders_sender FOREIGN KEY (sender_user_id)  REFERENCES users(id)   ON DELETE SET NULL,
    INDEX idx_gift_orders_sender (sender_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
