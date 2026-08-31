-- Restaurant service V4: coupons, promotions, promo banners (Priority 2)
CREATE TABLE coupons (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(40)    NOT NULL,
    discount_type   VARCHAR(20)    NOT NULL,
    discount_value  NUMERIC(10,2)  NOT NULL,
    min_order_amount NUMERIC(10,2) NOT NULL DEFAULT 0,
    max_discount    NUMERIC(10,2),
    valid_from      TIMESTAMP(6),
    valid_until     TIMESTAMP(6),
    active          BOOLEAN        NOT NULL DEFAULT true,
    created_at      TIMESTAMP(6)   NOT NULL,
    CONSTRAINT uk_coupon_code UNIQUE (code)
);

CREATE TABLE coupon_usages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    coupon_id   BIGINT       NOT NULL REFERENCES coupons (id),
    customer_id BIGINT       NOT NULL,
    order_id    BIGINT,
    discount    NUMERIC(10,2) NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_coupon_usage UNIQUE (coupon_id, customer_id, order_id)
);
CREATE INDEX idx_coupon_usages_customer ON coupon_usages (customer_id);

CREATE TABLE promotion_campaigns (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120)   NOT NULL,
    description TEXT,
    discount_pct NUMERIC(5,2)  NOT NULL,
    max_discount NUMERIC(10,2),
    starts_at   TIMESTAMP(6)   NOT NULL,
    ends_at     TIMESTAMP(6)   NOT NULL,
    active      BOOLEAN        NOT NULL DEFAULT true,
    created_at  TIMESTAMP(6)   NOT NULL
);

CREATE TABLE promo_banners (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    image_url   VARCHAR(500),
    campaign_id BIGINT       REFERENCES promotion_campaigns (id),
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_promo_banners_active ON promo_banners (active);