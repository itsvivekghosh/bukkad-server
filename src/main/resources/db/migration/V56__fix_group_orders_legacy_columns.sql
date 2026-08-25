-- V56__fix_group_orders_legacy_columns.sql
-- Fixes the group_orders table shape for the redesigned GroupOrder entity.
--
-- V28 created group_orders with a legacy "orders"-shaped schema whose NOT NULL
-- columns (order_number, restaurant_id, primary_customer_id, updated_at) have
-- no defaults. The current entity only writes host_user_id/title/status/
-- created_at, so every INSERT failed with "Field 'order_number' doesn't have a
-- default value" -> 500 on POST /api/v1/customers/group-orders.
--
-- This migration makes the unused legacy columns nullable and drops their
-- foreign keys so new rows can be inserted and legacy rows can be cleaned up
-- without FK interference. It is idempotent for databases that already ran it.

SET @schema_name = DATABASE();

-- 1) Make legacy NOT NULL columns nullable.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'order_number' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN order_number VARCHAR(255) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'restaurant_id' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN restaurant_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'primary_customer_id' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN primary_customer_id BIGINT NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND COLUMN_NAME = 'updated_at' AND IS_NULLABLE = 'NO') > 0,
    'ALTER TABLE group_orders MODIFY COLUMN updated_at DATETIME(6) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) Drop the legacy foreign keys referencing restaurants/customers (the
--    columns they guard are no longer written by the application).
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND CONSTRAINT_NAME = 'fk_group_order_restaurant') > 0,
    'ALTER TABLE group_orders DROP FOREIGN KEY fk_group_order_restaurant',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'group_orders'
       AND CONSTRAINT_NAME = 'fk_group_order_customer') > 0,
    'ALTER TABLE group_orders DROP FOREIGN KEY fk_group_order_customer',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
