-- ============================================================================
-- V63: Order categorization + category-pruned indexes
-- ----------------------------------------------------------------------------
-- Categorizes EVERY order row (live and archived) with a MySQL STORED
-- generated column derived from status, so categorization can never drift
-- from the status transition it mirrors and needs no application code on any
-- write path:
--
--   LIVE      -> SCHEDULED, PLACED, CONFIRMED, PREPARING,
--                READY_FOR_PICKUP, OUT_FOR_DELIVERY
--   FULFILLED -> DELIVERED
--   CANCELLED -> CANCELLED, REFUNDED
--
-- Optimization strategy (index-first alternative to application-level
-- sharding; `orders_archive` is already RANGE COLUMNS partitioned per
-- quarter by V55, which provides the cold-data pruning):
--   * category-leading composite indexes let "live orders" dashboards,
--     tracking screens and agent queues prune FULFILLED/CANCELLED history
--     without touching it.
--   * status-only hot paths (DELIVERED analytics) keep their existing
--     (status, created_at) / covering indexes from V60/V61.
-- ============================================================================

SET @schema_name = DATABASE();

-- ----------------------------------------------------------------------------
-- 1. Generated category column on the live orders table
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'order_category') = 0,
    CONCAT(
      'ALTER TABLE orders ADD COLUMN order_category ENUM(''LIVE'',''FULFILLED'',''CANCELLED'') ',
      'GENERATED ALWAYS AS (CASE ',
      'WHEN status IN (''SCHEDULED'',''PLACED'',''CONFIRMED'',''PREPARING'',''READY_FOR_PICKUP'',''OUT_FOR_DELIVERY'') THEN ''LIVE'' ',
      'WHEN status = ''DELIVERED'' THEN ''FULFILLED'' ',
      'ELSE ''CANCELLED'' END) STORED'),
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 2. Generated category column on the archive (its status is a plain VARCHAR,
--    so unknown statuses degrade to CANCELLED exactly like the live table)
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders_archive' AND COLUMN_NAME = 'order_category') = 0,
    CONCAT(
      'ALTER TABLE orders_archive ADD COLUMN order_category ENUM(''LIVE'',''FULFILLED'',''CANCELLED'') ',
      'GENERATED ALWAYS AS (CASE ',
      'WHEN status IN (''SCHEDULED'',''PLACED'',''CONFIRMED'',''PREPARING'',''READY_FOR_PICKUP'',''OUT_FOR_DELIVERY'') THEN ''LIVE'' ',
      'WHEN status = ''DELIVERED'' THEN ''FULFILLED'' ',
      'ELSE ''CANCELLED'' END) STORED'),
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 3. Category-pruned indexes on the hot table
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_order_customer_category') = 0,
    'CREATE INDEX idx_order_customer_category ON orders (customer_id, order_category, created_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_order_restaurant_category') = 0,
    'CREATE INDEX idx_order_restaurant_category ON orders (restaurant_id, order_category, created_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_order_agent_category') = 0,
    'CREATE INDEX idx_order_agent_category ON orders (delivery_agent_id, order_category)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_order_category_created') = 0,
    'CREATE INDEX idx_order_category_created ON orders (order_category, created_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ----------------------------------------------------------------------------
-- 4. Category index on the archive for cold-path subset queries
-- ----------------------------------------------------------------------------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders_archive' AND INDEX_NAME = 'idx_orders_archive_category') = 0,
    'CREATE INDEX idx_orders_archive_category ON orders_archive (customer_id, order_category, created_at)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
