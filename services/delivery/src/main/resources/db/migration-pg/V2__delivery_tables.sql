CREATE TABLE delivery_agents (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    phone      VARCHAR(20),
    is_active  BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE delivery_assignments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id      BIGINT       NOT NULL,
    agent_id      BIGINT       NOT NULL REFERENCES delivery_agents (id),
    status        VARCHAR(20)  NOT NULL,
    assigned_at   TIMESTAMP(6) NOT NULL,
    picked_up_at  TIMESTAMP(6),
    delivered_at  TIMESTAMP(6),
    created_at    TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_assign_order ON delivery_assignments (order_id);
CREATE INDEX idx_assign_agent ON delivery_assignments (agent_id, status);