-- Admin-analytics service V3: churn, experiment, data export (Batch E depth)
CREATE TABLE churn_scores (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   BIGINT         NOT NULL,
    score         DOUBLE PRECISION NOT NULL,
    model_version VARCHAR(20)    NOT NULL,
    features_json TEXT,
    computed_at   TIMESTAMP(6)   NOT NULL,
    created_at    TIMESTAMP(6)   NOT NULL
);
CREATE INDEX idx_churn_customer ON churn_scores (customer_id, computed_at);

CREATE TABLE experiment_exposures (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   BIGINT       NOT NULL,
    experiment    VARCHAR(100) NOT NULL,
    variant       VARCHAR(50)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_experiment_customer ON experiment_exposures (customer_id, experiment);

CREATE TABLE data_export_requests (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   BIGINT       NOT NULL,
    format        VARCHAR(10)  NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    file_url      VARCHAR(500),
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL
);