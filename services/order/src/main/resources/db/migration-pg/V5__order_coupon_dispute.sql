-- ============================================================================
-- Order service — V5: coupons, coupon usages, disputes (strangler P2 extraction)
-- ============================================================================
-- Extracted from the monolith's coupons / coupon_usages / disputes tables so the
-- gateway can cut /api/v1/coupons/** and the dispute surfaces over to this
-- service. The orders table gains the columns dispute auto-resolution needs
-- (order_number for human-readable responses, delivery timing for the
-- LATE_DELIVERY rule).
-- ============================================================================

CREATE TABLE coupons (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code                   VARCHAR(40)    NOT NULL,
    description            VARCHAR(255)   NOT NULL,
    discount_type          VARCHAR(20)    NOT NULL,
    discount_value         NUMERIC(10,2)  NOT NULL,
    minimum_order_amount   NUMERIC(10,2),
    maximum_discount_amount NUMERIC(10,2),
    valid_from             TIMESTAMP(6)   NOT NULL,
    valid_until            TIMESTAMP(6)   NOT NULL,
    usage_limit            INTEGER,
    used_count             INTEGER        NOT NULL DEFAULT 0,
    per_user_limit         INTEGER,
    active                 BOOLEAN        NOT NULL DEFAULT true,
    restaurant_id          BIGINT,
    CONSTRAINT uk_coupon_code UNIQUE (code)
);

CREATE INDEX idx_coupon_restaurant ON coupons (restaurant_id);
CREATE INDEX idx_coupon_active_valid ON coupons (active, valid_from, valid_until);

CREATE TABLE coupon_usages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coupon_id   BIGINT        NOT NULL REFERENCES coupons (id) ON DELETE CASCADE,
    customer_id BIGINT        NOT NULL,
    order_id    BIGINT        REFERENCES orders (id) ON DELETE SET NULL,
    used_at     TIMESTAMP(6)  NOT NULL
);

CREATE INDEX idx_coupon_usage_coupon_customer ON coupon_usages (coupon_id, customer_id);

CREATE TABLE disputes (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id           BIGINT        NOT NULL UNIQUE REFERENCES orders (id) ON DELETE CASCADE,
    type               VARCHAR(20)   NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    customer_evidence  TEXT,
    rider_evidence     TEXT,
    restaurant_evidence TEXT,
    resolution_notes   TEXT,
    resolution         VARCHAR(20),
    refund_amount      NUMERIC(12,2),
    resolved_by        BIGINT,
    resolved_at        TIMESTAMP(6),
    created_at         TIMESTAMP(6)  NOT NULL
);

CREATE INDEX idx_dispute_status ON disputes (status);
CREATE INDEX idx_dispute_created ON disputes (created_at);

-- Dispute auto-resolution needs order number + delivery timing on the order.
ALTER TABLE orders ADD COLUMN order_number VARCHAR(30);
ALTER TABLE orders ADD COLUMN delivered_at TIMESTAMP(6);
ALTER TABLE orders ADD COLUMN estimated_delivery_at TIMESTAMP(6);
