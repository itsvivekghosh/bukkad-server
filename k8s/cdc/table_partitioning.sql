-- =============================================================================
# Table Partitioning for High-Volume Tables (P5-02)
#
# Partitions orders, order_items, payments, and outbox_events tables by created_at
# month to enable partition pruning, faster vacuum, and efficient archival.
#
# Applied via Flyway migration: V__partition_high_volume_tables.sql
-- =============================================================================

-- ── orders: partitioned by created_at month ──
CREATE TABLE orders_original (LIKE orders INCLUDING ALL);
INSERT INTO orders_original SELECT * FROM orders;
DROP TABLE orders CASCADE;

CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
    total_amount NUMERIC(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivery_agent_id BIGINT,
    delivered_at TIMESTAMPTZ,
    wallet_amount_used NUMERIC(10,2) DEFAULT 0
) PARTITION BY RANGE (created_at);

-- Create monthly partitions for the current and next 12 months
DO $$
DECLARE
    month_start DATE := date_trunc('month', CURRENT_DATE)::DATE;
    i INT;
BEGIN
    FOR i IN 0..12 LOOP
        EXECUTE format(
            'CREATE TABLE orders_%s PARTITION OF orders FOR VALUES FROM (''%s''::timestamptz) TO (''%s''::timestamptz)',
            to_char(month_start + (i || ' months')::INTERVAL, 'YYYY_MM'),
            month_start + (i || ' months')::INTERVAL,
            month_start + ((i + 1) || ' months')::INTERVAL
        );
    END LOOP;
END $$;

-- Migrate data from orders_original into partitioned orders
INSERT INTO orders SELECT * FROM orders_original;
DROP TABLE orders_original;

-- Recreate indexes and constraints
CREATE INDEX idx_orders_customer ON orders(customer_id, created_at);
CREATE INDEX idx_orders_restaurant ON orders(restaurant_id, created_at);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_delivery_agent ON orders(delivery_agent_id);

-- ── order_items: partitioned by created_at month ──
CREATE TABLE order_items_original (LIKE order_items INCLUDING ALL);
INSERT INTO order_items_original SELECT * FROM order_items;
DROP TABLE order_items CASCADE;

CREATE TABLE order_items (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    menu_item_id BIGINT NOT NULL,
    item_name VARCHAR(255) NOT NULL,
    unit_price NUMERIC(10,2) NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);

DO $$
DECLARE
    month_start DATE := date_trunc('month', CURRENT_DATE)::DATE;
    i INT;
BEGIN
    FOR i IN 0..12 LOOP
        EXECUTE format(
            'CREATE TABLE order_items_%s PARTITION OF order_items FOR VALUES FROM (''%s''::timestamptz) TO (''%s''::timestamptz)',
            to_char(month_start + (i || ' months')::INTERVAL, 'YYYY_MM'),
            month_start + (i || ' months')::INTERVAL,
            month_start + ((i + 1) || ' months')::INTERVAL
        );
    END LOOP;
END $$;

INSERT INTO order_items SELECT * FROM order_items_original;
DROP TABLE order_items_original;

CREATE INDEX idx_order_items_order_id ON order_items(order_id);
CREATE INDEX idx_order_items_menu_item_id ON order_items(menu_item_id);

-- ── payments: partitioned by created_at month ──
CREATE TABLE payments_original (LIKE payments INCLUDING ALL);
INSERT INTO payments_original SELECT * FROM payments;
DROP TABLE payments CASCADE;

CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT,
    customer_id BIGINT NOT NULL,
    amount NUMERIC(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method VARCHAR(50),
    provider_ref VARCHAR(255),
    idempotency_key VARCHAR(255) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
) PARTITION BY RANGE (created_at);

DO $$
DECLARE
    month_start DATE := date_trunc('month', CURRENT_DATE)::DATE;
    i INT;
BEGIN
    FOR i IN 0..12 LOOP
        EXECUTE format(
            'CREATE TABLE payments_%s PARTITION OF payments FOR VALUES FROM (''%s''::timestamptz) TO (''%s''::timestamptz)',
            to_char(month_start + (i || ' months')::INTERVAL, 'YYYY_MM'),
            month_start + (i || ' months')::INTERVAL,
            month_start + ((i + 1) || ' months')::INTERVAL
        );
    END LOOP;
END $$;

INSERT INTO payments SELECT * FROM payments_original;
DROP TABLE payments_original;

CREATE INDEX idx_payments_order_id ON payments(order_id);
CREATE INDEX idx_payments_customer_id ON payments(customer_id);
CREATE INDEX idx_payments_idempotency_key ON payments(idempotency_key);

-- ── outbox_events: partitioned by created_at month ──
CREATE TABLE outbox_events_original (LIKE outbox_events INCLUDING ALL);
INSERT INTO outbox_events_original SELECT * FROM outbox_events;
DROP TABLE outbox_events CASCADE;

CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    partition_id INTEGER NOT NULL DEFAULT 0
) PARTITION BY RANGE (created_at);

DO $$
DECLARE
    month_start DATE := date_trunc('month', CURRENT_DATE)::DATE;
    i INT;
BEGIN
    FOR i IN 0..12 LOOP
        EXECUTE format(
            'CREATE TABLE outbox_events_%s PARTITION OF outbox_events FOR VALUES FROM (''%s''::timestamptz) TO (''%s''::timestamptz)',
            to_char(month_start + (i || ' months')::INTERVAL, 'YYYY_MM'),
            month_start + (i || ' months')::INTERVAL,
            month_start + ((i + 1) || ' months')::INTERVAL
        );
    END LOOP;
END $$;

INSERT INTO outbox_events SELECT * FROM outbox_events_original;
DROP TABLE outbox_events_original;

CREATE INDEX idx_outbox_status_created ON outbox_events(status, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_partition ON outbox_events(partition_id, status, created_at);

-- ── Archived table for old partitions ──
CREATE TABLE orders_archive (
    id BIGINT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    restaurant_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    total_amount NUMERIC(10,2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- ── Partition maintenance: detach old partitions monthly ──
-- Run via cron job: detach partitions older than 12 months and move to archive
-- Example SQL:
--   ALTER TABLE orders DETACH PARTITION orders_2026_01;
--   INSERT INTO orders_archive SELECT * FROM ONLY orders_2026_01;
--   DROP TABLE orders_2026_01;
