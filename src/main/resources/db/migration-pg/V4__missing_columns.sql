-- V4: Missing columns from the MySQL ALTER series (V9–V56) that are not yet
-- in the monolith PG baseline. Each block adds the columns a specific MySQL
-- migration introduced.

-- V9: menu items stock, tip amount, customer referral
ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS stock_quantity INTEGER;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS tip_amount DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS referral_code VARCHAR(20);
ALTER TABLE customers ADD COLUMN IF NOT EXISTS referred_by_customer_id BIGINT;

-- V12: whatsapp notification preference
ALTER TABLE customer_notification_preferences ADD COLUMN IF NOT EXISTS whatsapp_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- V13: restaurant busy mode, loyalty tier, moderation, cancellation
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS busy_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS busy_until TIMESTAMP(6);
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS extra_prep_minutes INTEGER NOT NULL DEFAULT 0;
ALTER TABLE customers ADD COLUMN IF NOT EXISTS loyalty_tier VARCHAR(20) NOT NULL DEFAULT 'BRONZE';
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS moderation_status VARCHAR(20) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS owner_response TEXT;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(255);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS cancelled_by VARCHAR(30);

-- V14: free delivery threshold on zones
ALTER TABLE delivery_zones ADD COLUMN IF NOT EXISTS free_delivery_above DOUBLE PRECISION;

-- V15: promotion campaign columns
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS restaurant_id BIGINT;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS max_discount_amount DOUBLE PRECISION;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS flat_discount_amount DOUBLE PRECISION;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS free_delivery BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS priority INTEGER NOT NULL DEFAULT 0;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS usage_limit INTEGER;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS per_user_limit INTEGER;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS buy_quantity INTEGER;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS get_quantity INTEGER;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS get_discount_percent DOUBLE PRECISION;
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS target_segment VARCHAR(20);
ALTER TABLE promotion_campaigns ADD COLUMN IF NOT EXISTS applicable_menu_item_id BIGINT;

-- V26/V53: membership plan tier columns
ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS tier_level INTEGER NOT NULL DEFAULT 0;
ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS max_discount_percent DOUBLE PRECISION;
ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS referral_bonus_percent DOUBLE PRECISION;
ALTER TABLE membership_plans ADD COLUMN IF NOT EXISTS referral_max_per_month INTEGER;

-- V29/V30: restaurant onboarding + tenant
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS onboarding_status VARCHAR(30) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS onboarding_rejection_reason VARCHAR(500);
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS tenant_id BIGINT;
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS virtual_brand_name VARCHAR(100);

-- V31: TOTP MFA on users
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS totp_secret VARCHAR(64);

-- V45: menu item dietary flags
ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS is_jain BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS available_from TIME;
ALTER TABLE menu_items ADD COLUMN IF NOT EXISTS available_until TIME;

-- V49: rider bonus
ALTER TABLE rider_earnings ADD COLUMN IF NOT EXISTS bonus_amount DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE rider_earnings ADD COLUMN IF NOT EXISTS bonus_reason VARCHAR(100);

-- V56: phone-first registration (users trimmed, phone-verified)
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_verified_at TIMESTAMP(6);
ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_completed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS device_id VARCHAR(64);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS guest_phone VARCHAR(15);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS fulfillment_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY';

-- V11: restaurant commission
ALTER TABLE restaurants ADD COLUMN IF NOT EXISTS commission_percent DOUBLE PRECISION;

-- V2 (platform ops): outbox, gateway columns
ALTER TABLE orders ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS gateway_order_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS gateway_payment_id VARCHAR(100);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS customer_id BIGINT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS purpose VARCHAR(30) NOT NULL DEFAULT 'ORDER';
ALTER TABLE payments ADD COLUMN IF NOT EXISTS wallet_amount DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS gateway_amount DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS loyalty_points_redeemed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders ADD COLUMN IF NOT EXISTS wallet_amount_used DOUBLE PRECISION NOT NULL DEFAULT 0;
-- addresses: line1/line2 (missed by the initial converter)
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS address_line1 VARCHAR(500);
ALTER TABLE addresses ADD COLUMN IF NOT EXISTS address_line2 VARCHAR(500);

-- Materialized summary tables (refreshed by MaterializedViewRefreshService)
CREATE TABLE IF NOT EXISTS restaurant_ratings_summary (
    restaurant_id     BIGINT NOT NULL,
    average_rating    DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_reviews     BIGINT NOT NULL DEFAULT 0,
    positive_reviews  BIGINT NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurant_id)
);
CREATE INDEX IF NOT EXISTS idx_ratings_summary_rating ON restaurant_ratings_summary (average_rating DESC);

CREATE TABLE IF NOT EXISTS restaurant_order_stats (
    restaurant_id   BIGINT NOT NULL,
    total_orders    BIGINT NOT NULL DEFAULT 0,
    delivered_orders BIGINT NOT NULL DEFAULT 0,
    cancelled_orders BIGINT NOT NULL DEFAULT 0,
    total_revenue   DOUBLE PRECISION NOT NULL DEFAULT 0,
    avg_order_value DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurant_id)
);

-- Partner API keys (ApiKey entity)
CREATE TABLE IF NOT EXISTS api_keys (
    id         BIGINT NOT NULL GENERATED BY DEFAULT AS IDENTITY,
    name       VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(20) NOT NULL,
    key_hash   CHAR(64) NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    partner_id BIGINT,
    scopes     VARCHAR(500),
    expires_at TIMESTAMP(6),
    last_used_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_api_keys_prefix UNIQUE (key_prefix),
    CONSTRAINT uk_api_keys_hash UNIQUE (key_hash)
);
CREATE INDEX IF NOT EXISTS idx_api_keys_status ON api_keys (status);
CREATE INDEX IF NOT EXISTS idx_api_keys_partner ON api_keys (partner_id);

-- Legacy group-order participant join table (referenced by JPA metamodel)
CREATE TABLE IF NOT EXISTS group_order_participants (
    group_order_id BIGINT NOT NULL,
    customer_id    BIGINT NOT NULL,
    PRIMARY KEY (group_order_id, customer_id)
);
CREATE INDEX IF NOT EXISTS idx_gop_customer ON group_order_participants (customer_id);

-- V63 order-category indexes (hot live-order dashboards, tracking, agent queues)
CREATE INDEX IF NOT EXISTS idx_order_customer_category ON orders (customer_id, order_category, created_at);
CREATE INDEX IF NOT EXISTS idx_order_restaurant_category ON orders (restaurant_id, order_category, created_at);
CREATE INDEX IF NOT EXISTS idx_order_agent_category ON orders (delivery_agent_id, order_category);
CREATE INDEX IF NOT EXISTS idx_order_category_created ON orders (order_category, created_at);
