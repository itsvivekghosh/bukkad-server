-- V61__covering_high_traffic_indexes.sql
-- Additional covering indexes for high-traffic hot paths missed by V60.
-- All statements idempotent via information_schema.

SET @schema_name = DATABASE();

-- orders(customer_id, status, created_at) — customer history count+sum+history all share this prefix
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_customer_status_created') = 0,
    'CREATE INDEX idx_orders_customer_status_created ON orders (customer_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- orders(delivery_agent_id, status, created_at) — rider app polls my orders
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_agent_status_created') = 0,
    'CREATE INDEX idx_orders_agent_status_created ON orders (delivery_agent_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- orders_archive covering for retention
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders_archive' AND INDEX_NAME = 'idx_orders_archive_restaurant') = 0,
    'CREATE INDEX idx_orders_archive_restaurant ON orders_archive (restaurant_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders_archive' AND INDEX_NAME = 'idx_orders_archive_created') = 0,
    'CREATE INDEX idx_orders_archive_created ON orders_archive (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- restaurants(tenant_id, onboarding_status, is_active) — multi-tenant approved list
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurants' AND INDEX_NAME = 'idx_restaurant_tenant_status') = 0,
    'CREATE INDEX idx_restaurant_tenant_status ON restaurants (tenant_id, onboarding_status, is_active)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- rider_earnings(agent_id, created_at) — without status for history pagination deep pages
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND INDEX_NAME = 'idx_rider_earning_agent_created') = 0,
    'CREATE INDEX idx_rider_earning_agent_created ON rider_earnings (agent_id, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- support_tickets / disputes created_at for admin lists
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'support_tickets' AND INDEX_NAME = 'idx_support_created') = 0,
    'CREATE INDEX idx_support_created ON support_tickets (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'disputes' AND INDEX_NAME = 'idx_dispute_created') = 0,
    'CREATE INDEX idx_dispute_created ON disputes (created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- cart_items composite unique to prevent duplicate race
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'cart_items' AND INDEX_NAME = 'uk_cart_item_cart_menu') = 0,
    'CREATE UNIQUE INDEX uk_cart_item_cart_menu ON cart_items (cart_id, menu_item_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- coupons active/restaurant/valid covering
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'coupons' AND INDEX_NAME = 'idx_coupon_active_restaurant_valid') = 0,
    'CREATE INDEX idx_coupon_active_restaurant_valid ON coupons (active, restaurant_id, valid_from, valid_until)',
    'SELECT 1'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
