-- V44__subscription_plans.sql
-- Adds recurring subscription meal plan support. Customers subscribe to a weekly
-- meal plan (restaurant + items + weekday + delivery time); the scheduler
-- materialises the next due subscription into a real scheduled order.
--
--   subscription_plans       : weekly plan definition (status, schedule, items)
--   subscription_deliveries  : per-instance delivery tracking (PENDING/PLACED/
--                             SKIPPED/FAILED)
--
-- Both tables are added idempotently (information_schema guards) so the
-- migration is safe on databases where they may already exist from a manual
-- hotfix or parallel branch.

SET @schema_name = DATABASE();

-- ==================== subscription_plans ====================

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'subscription_plans') = 0,
    'CREATE TABLE subscription_plans (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        user_id BIGINT NOT NULL,
        restaurant_id BIGINT NOT NULL,
        title VARCHAR(100),
        items_json TEXT,
        weekday VARCHAR(10) NOT NULL COMMENT \'MON/TUE/WED/THU/FRI/SAT/SUN\',
        delivery_time TIME NOT NULL,
        delivery_address_id BIGINT NOT NULL,
        payment_method VARCHAR(30) NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT \'ACTIVE\' COMMENT \'ACTIVE/PAUSED/CANCELLED\',
        start_date DATE NOT NULL,
        next_delivery_date DATE,
        created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
        INDEX idx_sub_user (user_id),
        INDEX idx_sub_status (status)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ==================== subscription_deliveries ====================

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'subscription_deliveries') = 0,
    'CREATE TABLE subscription_deliveries (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        subscription_plan_id BIGINT NOT NULL,
        order_id BIGINT,
        scheduled_date DATE NOT NULL,
        status VARCHAR(20) NOT NULL DEFAULT \'PENDING\' COMMENT \'PENDING/PLACED/SKIPPED/FAILED\',
        UNIQUE KEY uq_sub_date (subscription_plan_id, scheduled_date),
        INDEX idx_sub_del_plan (subscription_plan_id),
        INDEX idx_sub_del_status (status),
        CONSTRAINT fk_sub_del_plan FOREIGN KEY (subscription_plan_id) REFERENCES subscription_plans(id) ON DELETE CASCADE,
        CONSTRAINT fk_sub_del_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE SET NULL
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;