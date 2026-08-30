-- V52__additional_indexes.sql
-- Adds additional indexes for query performance optimization
-- Verified against the actual schema:
--   settlement_runs(started_at), coupon_usages(customer_id), reviews(created_at)
--   orders(restaurant_id, status), payments(order_id, status),
--   menu_items(category_id, available)

SET @schema_name = DATABASE();

-- settlement_runs.started_at index for time-range queries on settlement runs
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'settlement_runs' AND INDEX_NAME = 'idx_settlement_runs_started_at') = 0,
    'CREATE INDEX idx_settlement_runs_started_at ON settlement_runs (started_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- coupon_usages.customer_id index for user-centric coupon queries
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'coupon_usages' AND INDEX_NAME = 'idx_coupon_usages_customer_id') = 0,
    'CREATE INDEX idx_coupon_usages_customer_id ON coupon_usages (customer_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- reviews.created_at index for time-based review queries
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'idx_reviews_created_at') = 0,
    'CREATE INDEX idx_reviews_created_at ON reviews (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- orders.restaurant_id + status for restaurant order queries
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_restaurant_status') = 0,
    'CREATE INDEX idx_orders_restaurant_status ON orders (restaurant_id, status)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- payments.order_id + status for payment lookups
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_order_status') = 0,
    'CREATE INDEX idx_payments_order_status ON payments (order_id, status)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.category_id + available for menu listing
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND INDEX_NAME = 'idx_menu_items_category_available') = 0,
    'CREATE INDEX idx_menu_items_category_available ON menu_items (category_id, available)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;