-- V37__performance_indexes.sql
-- Index audit (feature #15): adds composite indexes for the most common
-- production query patterns that were previously single-column or missing:
--
--   1. orders(status, created_at)         -- kitchen queue, admin status dashboards,
--                                            scheduled-order dispatch scans
--   2. orders(customer_id, created_at)    -- customer order-history listing (paged)
--   3. order_items(menu_item_id)          -- menu-item sales analytics
--   4. fraud_events(created_at)           -- sliding-window cleanup / retention scans
--
-- All statements are idempotent (information_schema guards + prepared
-- statements), matching the V31 style, so they are safe on databases where
-- the index may already exist from a manual hotfix.

SET @schema_name = DATABASE();

-- orders(status, created_at)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders'
       AND INDEX_NAME = 'idx_order_status_created') = 0,
    'CREATE INDEX idx_order_status_created ON orders(status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders(customer_id, created_at)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders'
       AND INDEX_NAME = 'idx_order_customer_created') = 0,
    'CREATE INDEX idx_order_customer_created ON orders(customer_id, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- order_items(menu_item_id)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'order_items'
       AND INDEX_NAME = 'idx_order_item_menu_item') = 0,
    'CREATE INDEX idx_order_item_menu_item ON order_items(menu_item_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- fraud_events(created_at)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'fraud_events'
       AND INDEX_NAME = 'idx_fraud_created') = 0,
    'CREATE INDEX idx_fraud_created ON fraud_events(created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
