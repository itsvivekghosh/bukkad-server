-- Admin service V5: api keys, feature flags (Batch 5)
CREATE TABLE api_keys (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    key_hash    VARCHAR(64)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    expires_at  TIMESTAMP(6) NOT NULL,
    created_at  TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_api_key_hash UNIQUE (key_hash)
);

CREATE TABLE feature_flags (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    flag_name  VARCHAR(100) NOT NULL,
    enabled    BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_feature_flag_name UNIQUE (flag_name)
);