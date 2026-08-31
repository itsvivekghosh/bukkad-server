-- ============================================================================
-- Payment service — owned tables (bhukkad_payments), P5
-- ============================================================================
-- V1 is the platform-lib platform baseline (outbox, idempotency). This V2
-- adds the payment/wallet domain tables. wallet_balances is the authoritative
-- balance row (plan §2.3 / V64 move); idempotency_records (V1) guards the
-- money path against double-credit/refund.
-- ============================================================================

CREATE TABLE payments (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    customer_id    BIGINT         NOT NULL,
    amount         NUMERIC(12,2)  NOT NULL,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'INR',
    status         VARCHAR(20)    NOT NULL,
    provider       VARCHAR(50),
    provider_ref   VARCHAR(100),
    created_at     TIMESTAMP(6)   NOT NULL,
    updated_at     TIMESTAMP(6)   NOT NULL
);

CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_customer ON payments (customer_id, created_at);
CREATE INDEX idx_payments_status ON payments (status);

CREATE TABLE wallet_balances (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT         NOT NULL,
    balance     NUMERIC(12,2)  NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP(6)   NOT NULL,
    CONSTRAINT uk_wallet_customer UNIQUE (customer_id)
);

CREATE TABLE wallet_transactions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT         NOT NULL,
    type        VARCHAR(20)    NOT NULL,
    amount      NUMERIC(12,2)  NOT NULL,
    balance_after NUMERIC(12,2) NOT NULL,
    reference   VARCHAR(100),
    created_at  TIMESTAMP(6)   NOT NULL
);

CREATE INDEX idx_wallet_tx_customer ON wallet_transactions (customer_id, created_at);