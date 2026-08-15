-- V15: Promotions engine — campaign rules, usage tracking, banner scheduling

ALTER TABLE promotion_campaigns
    ADD COLUMN restaurant_id BIGINT NULL,
    ADD COLUMN max_discount_amount DOUBLE NULL,
    ADD COLUMN flat_discount_amount DOUBLE NULL,
    ADD COLUMN free_delivery BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN priority INT NOT NULL DEFAULT 0,
    ADD COLUMN usage_limit INT NULL,
    ADD COLUMN per_user_limit INT NULL DEFAULT 1,
    ADD CONSTRAINT fk_campaign_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id);

CREATE TABLE campaign_usages (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    campaign_id BIGINT        NOT NULL,
    customer_id BIGINT        NOT NULL,
    order_id    BIGINT        NULL,
    used_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_usage_campaign FOREIGN KEY (campaign_id) REFERENCES promotion_campaigns(id),
    CONSTRAINT fk_usage_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_usage_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_usage_campaign (campaign_id),
    INDEX idx_usage_customer (campaign_id, customer_id)
);

-- Sample platform-wide lunch campaign
INSERT INTO promotion_campaigns (name, campaign_type, description, discount_percent, min_order_amount,
                                 is_active, priority, free_delivery, starts_at, ends_at)
VALUES ('Lunch Rush', 'PERCENT_OFF', '10% off lunch orders above ₹200', 10.0, 200.0, TRUE, 10, FALSE,
        NOW(), DATE_ADD(NOW(), INTERVAL 90 DAY));
