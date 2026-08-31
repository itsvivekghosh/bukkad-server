-- V60__high_traffic_indexes.sql
-- High-traffic production indexes. All statements are idempotent (information_schema guards)
-- so the migration is safe to re-run and safe on databases where a hotfix already
-- added the index manually.

SET @schema_name = DATABASE();

-- 1. payments.gateway_order_id — webhook lookup (PaymentServiceImpl.findByGatewayOrderId)
--    is on the critical payment path and previously did a full scan.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_gateway_order_id') = 0,
    'CREATE INDEX idx_payments_gateway_order_id ON payments (gateway_order_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- payments.gateway_payment_id — Razorpay payment lookup (refund / verification)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_gateway_payment_id') = 0,
    'CREATE INDEX idx_payments_gateway_payment_id ON payments (gateway_payment_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. orders(status, scheduled_at) — scheduled-order dispatch
--    `SELECT ... WHERE status='SCHEDULED' AND scheduled_at <= NOW()` runs every 60s
--    on the hot path. Single-column idx_order_scheduled_at is insufficient.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_status_scheduled_at') = 0,
    'CREATE INDEX idx_orders_status_scheduled_at ON orders (status, scheduled_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. orders(guest_phone) / orders(device_id) — guest checkout lookup
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_guest_phone') = 0,
    'CREATE INDEX idx_orders_guest_phone ON orders (guest_phone)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_device_id') = 0,
    'CREATE INDEX idx_orders_device_id ON orders (device_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. outbox_events(status, processing_started_at) — recovery of stale PROCESSING
--    `recoverStaleProcessing` does WHERE status='PROCESSING' AND processing_started_at < NOW()-threshold
--    Previously next_retry_at did not exist; use processing_started_at.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'outbox_events' AND INDEX_NAME = 'idx_outbox_status_processing') = 0,
    'CREATE INDEX idx_outbox_status_processing ON outbox_events (status, processing_started_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. rider_earnings(agent_id, status, created_at) — settlement sweep sorts by createdAt
--    extend existing (agent_id, status) with created_at for cursor pagination.
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND INDEX_NAME = 'idx_rider_earning_agent_status_created') = 0,
    'CREATE INDEX idx_rider_earning_agent_status_created ON rider_earnings (agent_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. restaurant_settlements(restaurant_id, status, created_at) — same for settlement history
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'restaurant_settlements' AND INDEX_NAME = 'idx_settlement_restaurant_status_created') = 0,
    'CREATE INDEX idx_settlement_restaurant_status_created ON restaurant_settlements (restaurant_id, status, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
