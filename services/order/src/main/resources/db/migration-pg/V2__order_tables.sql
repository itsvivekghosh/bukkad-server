-- ============================================================================
-- Order service — owned tables (bhukkad_orders), P4
-- ============================================================================
-- V1 is the platform-lib platform baseline (outbox, saga, idempotency).
-- This V2 adds the order domain tables.
-- ============================================================================

CREATE TABLE orders (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   BIGINT         NOT NULL,
    restaurant_id BIGINT         NOT NULL,
    status        VARCHAR(20)    NOT NULL,
    total_amount  NUMERIC(12,2)  NOT NULL,
    currency      VARCHAR(3)     NOT NULL DEFAULT 'INR',
    created_at    TIMESTAMP(6)   NOT NULL,
    updated_at    TIMESTAMP(6)   NOT NULL
);

CREATE INDEX idx_orders_customer ON orders (customer_id, created_at);
CREATE INDEX idx_orders_restaurant ON orders (restaurant_id, created_at);
CREATE INDEX idx_orders_status ON orders (status);

CREATE TABLE order_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    menu_item_id  BIGINT        NOT NULL,
    item_name     VARCHAR(200)  NOT NULL,
    unit_price    NUMERIC(10,2) NOT NULL,
    quantity      INTEGER       NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL
);

CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE order_timeline_events (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    event_type VARCHAR(50)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_timeline_order ON order_timeline_events (order_id, created_at);