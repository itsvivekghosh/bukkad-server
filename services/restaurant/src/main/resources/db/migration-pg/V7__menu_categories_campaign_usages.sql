-- ============================================================================
-- Restaurant service — Batch 4 wave 2: menu categories + campaign usages
-- ============================================================================
-- Service-local ports of the monolith's menu_categories and campaign_usages
-- tables. customer_id / order_id stay plain columns: customers and orders are
-- owned by other services (no cross-service FKs).
-- ============================================================================

CREATE TABLE menu_categories (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100)  NOT NULL,
    description   VARCHAR(500),
    restaurant_id BIGINT        NOT NULL REFERENCES restaurants (id),
    display_order INTEGER       DEFAULT 0,
    active        BOOLEAN       NOT NULL DEFAULT true
);

CREATE INDEX idx_category_restaurant ON menu_categories (restaurant_id);
CREATE INDEX idx_category_active ON menu_categories (active);
CREATE INDEX idx_category_restaurant_active ON menu_categories (restaurant_id, active);
CREATE INDEX idx_category_restaurant_order ON menu_categories (restaurant_id, display_order);

-- menu items gain their category link (plain column; category is owned here).
ALTER TABLE menu_items
    ADD COLUMN category_id BIGINT REFERENCES menu_categories (id);

CREATE INDEX idx_menu_item_category ON menu_items (category_id);

CREATE TABLE campaign_usages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    campaign_id BIGINT       NOT NULL REFERENCES promotion_campaigns (id),
    customer_id BIGINT       NOT NULL,
    order_id    BIGINT,
    used_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_usage_campaign ON campaign_usages (campaign_id);
CREATE INDEX idx_usage_customer ON campaign_usages (campaign_id, customer_id);
