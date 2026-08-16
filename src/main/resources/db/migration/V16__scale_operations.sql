-- V16: Scale ops — settlement automation runs, rider delivery batching

CREATE TABLE settlement_runs (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_type            VARCHAR(30)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    restaurants_settled INT           NOT NULL DEFAULT 0,
    agents_settled      INT           NOT NULL DEFAULT 0,
    total_amount        DOUBLE        NOT NULL DEFAULT 0,
    started_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at        TIMESTAMP     NULL,
    notes               TEXT
);

CREATE TABLE rider_delivery_batches (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id     BIGINT        NOT NULL,
    status       VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP     NULL,
    CONSTRAINT fk_batch_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id),
    INDEX idx_batch_agent (agent_id, status)
);

CREATE TABLE rider_delivery_batch_orders (
    batch_id         BIGINT NOT NULL,
    order_id         BIGINT NOT NULL,
    sequence_number  INT    NOT NULL,
    PRIMARY KEY (batch_id, order_id),
    CONSTRAINT fk_batch_order_batch FOREIGN KEY (batch_id) REFERENCES rider_delivery_batches(id),
    CONSTRAINT fk_batch_order_order FOREIGN KEY (order_id) REFERENCES orders(id),
    UNIQUE KEY uk_batch_order (order_id)
);
