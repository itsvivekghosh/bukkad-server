-- Order payment breakdown & loyalty tracking
ALTER TABLE orders
    ADD COLUMN loyalty_points_redeemed INT NOT NULL DEFAULT 0,
    ADD COLUMN wallet_amount_used DOUBLE NOT NULL DEFAULT 0;

-- Payment: split pay + wallet top-up
ALTER TABLE payments
    MODIFY COLUMN order_id BIGINT NULL,
    ADD COLUMN customer_id BIGINT NULL,
    ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'ORDER',
    ADD COLUMN wallet_amount DOUBLE NOT NULL DEFAULT 0,
    ADD COLUMN gateway_amount DOUBLE NOT NULL DEFAULT 0,
    ADD CONSTRAINT fk_payment_customer FOREIGN KEY (customer_id) REFERENCES customers(id);

CREATE INDEX idx_payment_customer ON payments(customer_id);
CREATE INDEX idx_payment_purpose ON payments(purpose);

-- Wallet ledger
CREATE TABLE wallet_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    payment_id BIGINT NULL,
    type VARCHAR(30) NOT NULL,
    amount DOUBLE NOT NULL,
    balance_after DOUBLE NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_wallet_tx_customer FOREIGN KEY (customer_id) REFERENCES customers(id),
    CONSTRAINT fk_wallet_tx_payment FOREIGN KEY (payment_id) REFERENCES payments(id)
);

CREATE INDEX idx_wallet_tx_customer ON wallet_transactions(customer_id, created_at);

-- Push notification device tokens
CREATE TABLE device_tokens (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    token VARCHAR(512) NOT NULL,
    platform VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL,
    CONSTRAINT fk_device_token_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT uk_device_token UNIQUE (token)
);

CREATE INDEX idx_device_token_user ON device_tokens(user_id, active);

-- Rider earnings ledger
CREATE TABLE rider_earnings (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    amount DOUBLE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    paid_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rider_earning_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    CONSTRAINT fk_rider_earning_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT uk_rider_earning_order UNIQUE (order_id)
);

CREATE INDEX idx_rider_earning_agent ON rider_earnings(agent_id, status);
