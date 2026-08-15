-- V13: Growth & operations — zones, support, invoices, membership, home feed, timeline, rider map

-- Delivery zones for serviceability and dynamic pricing
CREATE TABLE delivery_zones (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL,
    city            VARCHAR(100)  NOT NULL,
    center_latitude DOUBLE        NOT NULL,
    center_longitude DOUBLE       NOT NULL,
    radius_km       DOUBLE        NOT NULL DEFAULT 5.0,
    base_delivery_fee DOUBLE      NOT NULL DEFAULT 40.0,
    per_km_fee      DOUBLE        NOT NULL DEFAULT 5.0,
    surge_multiplier DOUBLE       NOT NULL DEFAULT 1.0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_delivery_zones_city (city),
    INDEX idx_delivery_zones_active (is_active)
);

-- Customer support tickets
CREATE TABLE support_tickets (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_number   VARCHAR(30)   NOT NULL UNIQUE,
    customer_id     BIGINT        NOT NULL,
    order_id        BIGINT        NULL,
    category        VARCHAR(50)   NOT NULL,
    subject         VARCHAR(255)  NOT NULL,
    description     TEXT,
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(20)   NOT NULL DEFAULT 'MEDIUM',
    resolution_notes TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_support_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_support_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_support_customer (customer_id),
    INDEX idx_support_status (status)
);

-- GST order invoices
CREATE TABLE order_invoices (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL UNIQUE,
    invoice_number  VARCHAR(40)   NOT NULL UNIQUE,
    subtotal        DOUBLE        NOT NULL,
    delivery_fee    DOUBLE        NOT NULL DEFAULT 0,
    tax_amount      DOUBLE        NOT NULL DEFAULT 0,
    cgst_amount     DOUBLE        NOT NULL DEFAULT 0,
    sgst_amount     DOUBLE        NOT NULL DEFAULT 0,
    discount_amount DOUBLE        NOT NULL DEFAULT 0,
    total_amount    DOUBLE        NOT NULL,
    restaurant_gstin VARCHAR(20),
    issued_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_invoice_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- Home feed promo banners
CREATE TABLE promo_banners (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title           VARCHAR(150)  NOT NULL,
    subtitle        VARCHAR(255),
    image_url       VARCHAR(500),
    action_type     VARCHAR(30)   NOT NULL DEFAULT 'NONE',
    action_target   VARCHAR(255),
    display_order   INT           NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_at       TIMESTAMP     NULL,
    ends_at         TIMESTAMP     NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_promo_active (is_active, display_order)
);

-- Membership plans (Bhukkad One style)
CREATE TABLE membership_plans (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL,
    description     TEXT,
    price_per_month DOUBLE        NOT NULL,
    free_delivery   BOOLEAN       NOT NULL DEFAULT TRUE,
    discount_percent DOUBLE       NOT NULL DEFAULT 0,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE customer_memberships (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id     BIGINT        NOT NULL,
    plan_id         BIGINT        NOT NULL,
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    starts_at       TIMESTAMP     NOT NULL,
    ends_at         TIMESTAMP     NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_membership_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_membership_plan FOREIGN KEY (plan_id) REFERENCES membership_plans(id),
    INDEX idx_membership_customer (customer_id, status)
);

-- Order timeline for support and tracking
CREATE TABLE order_timeline_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    event_type      VARCHAR(50)   NOT NULL,
    status          VARCHAR(30),
    message         VARCHAR(500),
    actor_id        BIGINT,
    actor_role      VARCHAR(30),
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_timeline_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_timeline_order (order_id, created_at)
);

-- Rider GPS snapshots for live map
CREATE TABLE rider_location_updates (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    agent_id        BIGINT        NOT NULL,
    latitude        DOUBLE        NOT NULL,
    longitude       DOUBLE        NOT NULL,
    recorded_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rider_loc_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_rider_loc_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    INDEX idx_rider_loc_order (order_id, recorded_at DESC)
);

-- Promotion campaigns beyond coupons
CREATE TABLE promotion_campaigns (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(120)  NOT NULL,
    campaign_type   VARCHAR(30)   NOT NULL,
    description     TEXT,
    discount_percent DOUBLE       NULL,
    min_order_amount DOUBLE       NULL,
    is_active       BOOLEAN       NOT NULL DEFAULT TRUE,
    starts_at       TIMESTAMP     NULL,
    ends_at         TIMESTAMP     NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Fraud / abuse event log
CREATE TABLE fraud_events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id     BIGINT        NULL,
    event_type      VARCHAR(50)   NOT NULL,
    device_fingerprint VARCHAR(128),
    ip_address      VARCHAR(45),
    details         TEXT,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_fraud_customer (customer_id),
    INDEX idx_fraud_type (event_type)
);

-- Restaurant busy mode
ALTER TABLE restaurants
    ADD COLUMN busy_mode BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN busy_until TIMESTAMP NULL,
    ADD COLUMN extra_prep_minutes INT NOT NULL DEFAULT 0;

-- Order cancellation metadata
ALTER TABLE orders
    ADD COLUMN cancellation_reason VARCHAR(255),
    ADD COLUMN cancelled_by VARCHAR(30);

-- Loyalty tier on customer
ALTER TABLE customers
    ADD COLUMN loyalty_tier VARCHAR(20) NOT NULL DEFAULT 'BRONZE';

-- Review moderation
ALTER TABLE reviews
    ADD COLUMN moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN owner_response TEXT;

-- Seed default membership plan and sample zone/banner
INSERT INTO membership_plans (name, description, price_per_month, free_delivery, discount_percent, is_active)
VALUES ('Bhukkad One', 'Free delivery and 5% off on all orders', 99.0, TRUE, 5.0, TRUE);

INSERT INTO delivery_zones (name, city, center_latitude, center_longitude, radius_km, base_delivery_fee, per_km_fee, surge_multiplier)
VALUES ('Bangalore Central', 'Bangalore', 12.9716, 77.5946, 15.0, 30.0, 4.0, 1.0);

INSERT INTO promo_banners (title, subtitle, image_url, action_type, action_target, display_order, is_active)
VALUES ('Welcome to Bhukkad', 'Order now and get fast delivery', NULL, 'NONE', NULL, 1, TRUE);
