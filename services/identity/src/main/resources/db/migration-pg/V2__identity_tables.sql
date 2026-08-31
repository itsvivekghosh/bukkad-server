-- ============================================================================
-- Identity service — owned tables (bhukkad_identity), P3
-- ============================================================================
-- V1 is the platform-lib platform baseline. This V2 adds identity tables.
-- ============================================================================

CREATE TABLE customers (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(100) NOT NULL,
    phone_number  VARCHAR(15),
    full_name     VARCHAR(100) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    email_verified BOOLEAN     NOT NULL DEFAULT false,
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_customer_email UNIQUE (email)
);

CREATE INDEX idx_customer_phone ON customers (phone_number);

CREATE TABLE addresses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT       NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    label       VARCHAR(50),
    line1       VARCHAR(255) NOT NULL,
    city        VARCHAR(100) NOT NULL,
    state       VARCHAR(100),
    zip_code    VARCHAR(10),
    is_default  BOOLEAN      NOT NULL DEFAULT false,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_address_customer ON addresses (customer_id);