-- Payment service V3: settlement tables (Batch D depth)
CREATE TABLE settlement_runs (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_date      DATE         NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    total_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL
);

CREATE TABLE restaurant_settlements (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_run_id BIGINT NOT NULL REFERENCES settlement_runs (id),
    restaurant_id   BIGINT         NOT NULL,
    order_count     INTEGER        NOT NULL,
    gross_amount    NUMERIC(12,2)  NOT NULL,
    commission      NUMERIC(12,2)  NOT NULL,
    net_amount      NUMERIC(12,2)  NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    created_at      TIMESTAMP(6)   NOT NULL
);
CREATE INDEX idx_settlement_restaurant ON restaurant_settlements (restaurant_id);