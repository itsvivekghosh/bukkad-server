-- Admin service V4: fraud review queue, analytics export tasks (Priority 8)
CREATE TABLE fraud_review_queue (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT       NOT NULL,
    rule        VARCHAR(100) NOT NULL,
    severity    VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    assigned_to VARCHAR(100),
    notes       TEXT,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_fraud_review_status ON fraud_review_queue (status);
CREATE INDEX idx_fraud_review_customer ON fraud_review_queue (customer_id);

CREATE TABLE analytics_export_tasks (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    export_type VARCHAR(50)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    file_url    VARCHAR(500),
    filters     TEXT,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);