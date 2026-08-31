CREATE TABLE audit_events (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_type  VARCHAR(30),
    actor_id    BIGINT,
    action      VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50),
    entity_id   BIGINT,
    details     TEXT,
    created_at  TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_audit_actor ON audit_events (actor_type, actor_id, created_at);
CREATE INDEX idx_audit_entity ON audit_events (entity_type, entity_id);

CREATE TABLE fraud_events (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT NOT NULL,
    rule         VARCHAR(100) NOT NULL,
    severity     VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    details      TEXT,
    created_at   TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_fraud_customer ON fraud_events (customer_id, created_at);
CREATE INDEX idx_fraud_status ON fraud_events (status);

CREATE TABLE restaurant_order_stats (
    restaurant_id BIGINT PRIMARY KEY,
    order_count   BIGINT NOT NULL DEFAULT 0,
    revenue       NUMERIC(14,2) NOT NULL DEFAULT 0,
    updated_at    TIMESTAMP(6) NOT NULL
);