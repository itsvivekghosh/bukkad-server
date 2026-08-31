-- Delivery service V4: rider location, batch dispatch, COD wallets (Priority 5)
CREATE TABLE rider_location_updates (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT       NOT NULL REFERENCES delivery_agents (id),
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_rider_location_agent ON rider_location_updates (agent_id, recorded_at);

CREATE TABLE agent_cod_wallets (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT        NOT NULL REFERENCES delivery_agents (id),
    balance     NUMERIC(12,2) NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP(6)  NOT NULL,
    CONSTRAINT uk_agent_cod_wallet UNIQUE (agent_id)
);

CREATE TABLE rider_delivery_batches (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id   BIGINT       NOT NULL REFERENCES delivery_agents (id),
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_rider_batch_agent ON rider_delivery_batches (agent_id, status);

CREATE TABLE rider_delivery_batch_orders (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id   BIGINT       NOT NULL REFERENCES rider_delivery_batches (id) ON DELETE CASCADE,
    order_id   BIGINT       NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_batch_order UNIQUE (batch_id, order_id)
);