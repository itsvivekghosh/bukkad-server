-- Restaurant service — V3: reviews, ratings, customizations, inventory,
-- pricing, menu versions, and tsvector search (Batch C depth).
CREATE TABLE reviews (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id     BIGINT         NOT NULL REFERENCES restaurants (id),
    customer_id       BIGINT         NOT NULL,
    rating            INTEGER        NOT NULL,
    food_rating       INTEGER,
    delivery_rating   INTEGER,
    comment           TEXT,
    status            VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    owner_response    TEXT,
    created_at        TIMESTAMP(6)   NOT NULL,
    updated_at        TIMESTAMP(6)   NOT NULL
);
CREATE INDEX idx_reviews_restaurant ON reviews (restaurant_id, created_at);
CREATE INDEX idx_reviews_status ON reviews (status);

CREATE TABLE review_images (
    review_id BIGINT NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    image_url VARCHAR(500) NOT NULL
);

CREATE TABLE menu_item_ratings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    menu_item_id  BIGINT       NOT NULL REFERENCES menu_items (id),
    customer_id   BIGINT       NOT NULL,
    rating        INTEGER      NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_menu_item_rating UNIQUE (menu_item_id, customer_id)
);

CREATE TABLE customization_choices (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    menu_item_id  BIGINT       NOT NULL REFERENCES menu_items (id),
    name          VARCHAR(100) NOT NULL,
    is_required   BOOLEAN      NOT NULL DEFAULT false,
    created_at    TIMESTAMP(6) NOT NULL
);

CREATE TABLE customization_options (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    choice_id   BIGINT         NOT NULL REFERENCES customization_choices (id) ON DELETE CASCADE,
    label       VARCHAR(100)   NOT NULL,
    price_delta NUMERIC(10,2)  NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(6)   NOT NULL
);

CREATE TABLE inventory_alerts (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    menu_item_id  BIGINT       NOT NULL REFERENCES menu_items (id),
    threshold     INTEGER      NOT NULL,
    current_stock INTEGER      NOT NULL,
    alert_type    VARCHAR(30)  NOT NULL,
    created_at    TIMESTAMP(6) NOT NULL
);

CREATE TABLE dynamic_pricing_rules (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT        NOT NULL REFERENCES restaurants (id),
    rule_name     VARCHAR(100)  NOT NULL,
    multiplier    NUMERIC(5,2)  NOT NULL,
    start_time    TIME,
    end_time      TIME,
    active        BOOLEAN       NOT NULL DEFAULT true,
    created_at    TIMESTAMP(6)  NOT NULL
);

CREATE TABLE menu_versions (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT       NOT NULL REFERENCES restaurants (id),
    version       INTEGER      NOT NULL,
    snapshot_json TEXT,
    created_at    TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_menu_versions_restaurant ON menu_versions (restaurant_id, version);

-- Search: PG port of MySQL FULLTEXT. A generated tsvector column + GIN index
-- serves the menu/restaurant search queries (plan §5.2 type mapping).
ALTER TABLE restaurants
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, ''))) STORED;
CREATE INDEX idx_restaurant_search ON restaurants USING GIN (search_vector);

ALTER TABLE menu_items
    ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english', coalesce(name, '') || ' ' || coalesce(description, ''))) STORED;
CREATE INDEX idx_menu_item_search ON menu_items USING GIN (search_vector);