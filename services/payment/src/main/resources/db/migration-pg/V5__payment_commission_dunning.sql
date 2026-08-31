-- Payment service V5: commission tiers, dunning state (Batch 3)
CREATE TABLE commission_tiers (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    min_order_count   INTEGER         NOT NULL,
    max_order_count   INTEGER,
    commission_pct    NUMERIC(5,2)    NOT NULL,
    active            BOOLEAN         NOT NULL DEFAULT true,
    created_at        TIMESTAMP(6)    NOT NULL
);

CREATE TABLE dunning_runs (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payment_id  BIGINT       NOT NULL REFERENCES payments (id),
    attempt     INTEGER      NOT NULL DEFAULT 1,
    status      VARCHAR(20)  NOT NULL,
    scheduled_at TIMESTAMP(6) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_dunning_payment_attempt UNIQUE (payment_id, attempt)
);