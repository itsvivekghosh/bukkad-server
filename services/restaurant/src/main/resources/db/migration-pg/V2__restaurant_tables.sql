-- ============================================================================
-- Restaurant service — owned tables (bhukkad_restaurants), P2
-- ============================================================================
-- V1 is the platform-lib platform baseline (outbox, DLQ, saga, idempotency).
-- This V2 adds the restaurant domain tables. The service's Flyway location
-- is classpath:db/migration-pg, which merges the common jar's V1 and this V2.
-- ============================================================================

CREATE TABLE cuisines (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE INDEX idx_cuisine_name ON cuisines (name);

CREATE TABLE restaurants (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    description TEXT,
    cuisine_id  BIGINT       NOT NULL REFERENCES cuisines (id),
    address     TEXT,
    phone       VARCHAR(20),
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    avg_rating  DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);

CREATE INDEX idx_restaurant_cuisine_active ON restaurants (cuisine_id, is_active);
CREATE INDEX idx_restaurant_name ON restaurants (name);

CREATE TABLE menu_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT        NOT NULL REFERENCES restaurants (id),
    name          VARCHAR(200)  NOT NULL,
    description   TEXT,
    price         NUMERIC(10,2) NOT NULL,
    is_available  BOOLEAN       NOT NULL DEFAULT true,
    created_at    TIMESTAMP(6)  NOT NULL,
    updated_at    TIMESTAMP(6)  NOT NULL
);

CREATE INDEX idx_menu_restaurant ON menu_items (restaurant_id, is_available);
CREATE INDEX idx_menu_restaurant_name ON menu_items (restaurant_id, name);

CREATE TABLE restaurant_ratings_summary (
    restaurant_id BIGINT PRIMARY KEY REFERENCES restaurants (id),
    avg_rating    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    review_count  INTEGER          NOT NULL DEFAULT 0
);