-- Production reliability: optimistic locking, outbox, idempotency, payment gateway columns

ALTER TABLE orders
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payments
    ADD COLUMN gateway_order_id VARCHAR(100),
    ADD COLUMN gateway_payment_id VARCHAR(100),
    ADD COLUMN idempotency_key VARCHAR(128),
    ADD UNIQUE INDEX idx_payment_idempotency_key (idempotency_key);

CREATE TABLE outbox_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_outbox_status_created (status, created_at),
    INDEX idx_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(128) NOT NULL,
    scope VARCHAR(50) NOT NULL,
    owner_id BIGINT,
    status VARCHAR(20) NOT NULL,
    response_payload TEXT,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_scope_key (scope, idempotency_key),
    INDEX idx_idempotency_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
