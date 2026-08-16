-- Scheduled orders, live ETA, restaurant settlement ledger

ALTER TABLE orders
    ADD COLUMN scheduled_at DATETIME(6) NULL,
    ADD COLUMN live_eta_minutes INT NULL,
    ADD COLUMN live_eta_at DATETIME(6) NULL;

CREATE INDEX idx_order_scheduled_at ON orders(scheduled_at);

CREATE TABLE restaurant_settlements (
    id BIGINT NOT NULL AUTO_INCREMENT,
    restaurant_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    order_amount DOUBLE NOT NULL,
    commission_amount DOUBLE NOT NULL,
    net_amount DOUBLE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    settled_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_settlement_order (order_id),
    CONSTRAINT fk_settlement_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_settlement_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_settlement_restaurant_status (restaurant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
