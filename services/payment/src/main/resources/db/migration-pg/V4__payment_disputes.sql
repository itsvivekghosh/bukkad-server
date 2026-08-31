-- Payment service V4: disputes + refund strategy tables (Priority 3)
CREATE TABLE disputes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id  BIGINT       NOT NULL REFERENCES payments (id),
    customer_id BIGINT       NOT NULL,
    order_id    BIGINT       NOT NULL,
    reason      VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    resolution  VARCHAR(255),
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_disputes_payment ON disputes (payment_id);
CREATE INDEX idx_disputes_customer ON disputes (customer_id, status);