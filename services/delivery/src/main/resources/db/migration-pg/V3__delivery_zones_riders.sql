-- Delivery service V3: zones, rider ops, delivery proofs (Batch D depth)
CREATE TABLE delivery_zones (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMP(6) NOT NULL
);

CREATE TABLE city_configs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    city_name   VARCHAR(100) NOT NULL,
    currency    VARCHAR(3)   NOT NULL DEFAULT 'INR',
    timezone    VARCHAR(50)  NOT NULL DEFAULT 'Asia/Kolkata',
    created_at  TIMESTAMP(6) NOT NULL
);

CREATE TABLE zone_surge_rules (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    zone_id      BIGINT         NOT NULL REFERENCES delivery_zones (id),
    day_of_week  INTEGER,
    start_time   TIME           NOT NULL,
    end_time     TIME           NOT NULL,
    multiplier   NUMERIC(5,2)   NOT NULL,
    active       BOOLEAN        NOT NULL DEFAULT true,
    created_at   TIMESTAMP(6)   NOT NULL
);

CREATE TABLE agent_shifts (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT       NOT NULL REFERENCES delivery_agents (id),
    start_time  TIMESTAMP(6) NOT NULL,
    end_time    TIMESTAMP(6),
    status      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL
);

CREATE TABLE rider_earnings (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT        NOT NULL REFERENCES delivery_agents (id),
    order_id    BIGINT        NOT NULL,
    amount      NUMERIC(10,2) NOT NULL,
    status      VARCHAR(20)   NOT NULL,
    created_at  TIMESTAMP(6)  NOT NULL
);
CREATE INDEX idx_rider_earnings_agent ON rider_earnings (agent_id);

CREATE TABLE order_delivery_proofs (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id   BIGINT       NOT NULL,
    photo_url  VARCHAR(500),
    notes      VARCHAR(500),
    signature  TEXT,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_delivery_proof_order ON order_delivery_proofs (order_id);