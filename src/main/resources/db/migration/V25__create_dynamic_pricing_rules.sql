-- V25: Create dynamic_pricing_rules table for DynamicPricingRule entity
-- IF NOT EXISTS: safe when V24 previously shipped duplicate DDL (staging repair + re-apply)
CREATE TABLE IF NOT EXISTS dynamic_pricing_rules (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    active TINYINT(1) NOT NULL DEFAULT 1,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    day_of_week INT NOT NULL,
    discount_percent DOUBLE NOT NULL DEFAULT 0.0,
    surge_percent DOUBLE NOT NULL DEFAULT 0.0,
    min_order_amount DOUBLE NOT NULL DEFAULT 0.0,
    max_discount_amount DOUBLE NOT NULL DEFAULT 0.0,
    priority INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_dynamic_pricing_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
