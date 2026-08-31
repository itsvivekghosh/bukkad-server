-- ============================================================================
-- Bhukkad — monolith PostgreSQL baseline (strangler migration "before")
-- ============================================================================
-- Source of truth: docs/architecture-microservices-postgresql.md §5.2/§12 (P0).
--
-- This baseline is the PostgreSQL migration path for the MySQL monolith:
--   * PART 1 — the shared PLATFORM tables (outbox, DLQ, saga, idempotency),
--     copied verbatim from the platform-lib baseline
--     (services/platform-lib/src/main/resources/db/migration-pg/
--     V1__bhukkad_common_pg_baseline.sql) so the monolith PG database owns
--     everything it needs locally, exactly like every extracted service.
--   * PART 2 — the monolith CORE tables the strangler has NOT yet extracted to
--     any service (orders, order_items, order_item_customizations,
--     order_timeline_events, order_eta_snapshots, order_invoices, carts,
--     cart_items, cart_item_customizations, reviews, review_images) plus the
--     tables those schemas reference (menu_items, payments) and the
--     element-collection join tables (menu_item_tags, menu_item_allergens,
--     menu_item_ingredients, menu_item_images, review_images). This is a
--     MINIMAL baseline: the frozen MySQL V1..V64 set keeps the remaining ~70
--     monolith tables (customers, restaurants, addresses, ...) until their
--     owning service is extracted.
--
-- MySQL -> PostgreSQL type mapping applied here (plan §5.2):
--   BIGINT AUTO_INCREMENT      -> BIGINT GENERATED ALWAYS/BY DEFAULT AS IDENTITY
--   DATETIME(6)                -> TIMESTAMP(6)  (see timestamp decision below)
--   BIT(1) / TINYINT(1)        -> BOOLEAN
--   DOUBLE                     -> DOUBLE PRECISION
--   TEXT                       -> TEXT
--   ENUM(...) GENERATED ALWAYS AS (...) STORED
--                              -> VARCHAR(20) GENERATED ALWAYS AS (...) STORED
--   ON DELETE CASCADE (FK)     -> ON DELETE CASCADE
--   FOR UPDATE SKIP LOCKED     -> unchanged (PG-identical syntax)
--
-- Timestamp decision (plan §5.4): the JPA entities map LocalDateTime, which
-- Hibernate maps to TIMESTAMP WITHOUT TIME ZONE. So all monolith timestamp
-- columns use TIMESTAMP(6) (no TZ), NOT TIMESTAMPTZ. TIMESTAMPTZ is reserved
-- for columns the services model as Instant (business-UTC semantics); the
-- monolith core has none today. All values are written as UTC by the
-- application; the JDBC/JVM timezone is pinned to UTC in prod.
--
-- FK fidelity: FK constraints are declared ONLY between tables that exist in
-- this baseline. Columns that point at tables NOT yet extracted (customers,
-- restaurants, addresses, delivery_agents, coupons, menu_categories,
-- customization_choices, customization_options) are kept as plain BIGINT
-- columns WITHOUT FK constraints — matching how the extracted service
-- baselines (services/*/db/migration-pg) model cross-service references.
-- Referential integrity to those tables is enforced by the monolith's frozen
-- MySQL set until the strangler pulls each table into its service.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- PART 1: PLATFORM TABLES (copied verbatim from platform-lib V1)
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Outbox (write-side durability backbone, plan §6.1)
-- ---------------------------------------------------------------------------
CREATE TABLE outbox_events (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type            VARCHAR(80)  NOT NULL,
    aggregate_type        VARCHAR(50)  NOT NULL,
    aggregate_id          BIGINT       NOT NULL,
    payload               TEXT         NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    retry_count           INTEGER      NOT NULL DEFAULT 0,
    last_error            VARCHAR(1000),
    created_at            TIMESTAMP(6) NOT NULL,
    published_at          TIMESTAMP(6),
    processing_started_at TIMESTAMP(6)
);

CREATE INDEX idx_outbox_status_created ON outbox_events (status, created_at);
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

-- ---------------------------------------------------------------------------
-- Dead letter (outbox/Kafka events that exhausted retries, plan §6.2 *.dlq)
-- ---------------------------------------------------------------------------
CREATE TABLE dead_letter_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_type     VARCHAR(80)  NOT NULL,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   BIGINT       NOT NULL,
    payload        TEXT         NOT NULL,
    last_error     VARCHAR(1000),
    retry_count    INTEGER      NOT NULL DEFAULT 0,
    source         VARCHAR(20),
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMP(6) NOT NULL,
    requeued_at    TIMESTAMP(6)
);

CREATE INDEX idx_dlq_status_created ON dead_letter_events (status, created_at);
CREATE INDEX idx_dlq_aggregate ON dead_letter_events (aggregate_type, aggregate_id);

-- ---------------------------------------------------------------------------
-- Saga instances + steps (compensating transactions, plan §6.4)
-- ---------------------------------------------------------------------------
-- NOTE: saga payload/compensation columns use TEXT, NOT JSONB, because the
-- JPA entities map these as String fields with @Column(columnDefinition =
-- "JSON") for MySQL. Hibernate binds a String property as VARCHAR, which
-- PostgreSQL rejects for jsonb columns ("column is of type jsonb but expression
-- is of type character varying"). Since the saga stores only raw JSON strings
-- with no JSONB-specific operators (plan §2.4: "raw JSON, no JSON functions"),
-- TEXT is the correct PostgreSQL type for the entity contract. When the service
-- is extracted and the entity is modelled with @JdbcTypeCode(SqlTypes.JSON),
-- the column can be promoted to JSONB.
CREATE TABLE saga_instances (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_type    VARCHAR(50)  NOT NULL,
    saga_id      VARCHAR(100) NOT NULL,
    current_step VARCHAR(50),
    status       VARCHAR(20)  NOT NULL,
    payload      TEXT,
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT uq_saga_instances_saga_id UNIQUE (saga_id)
);

CREATE INDEX idx_saga_type_status ON saga_instances (saga_type, status);

CREATE TABLE saga_steps (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    saga_instance_id     BIGINT       NOT NULL,
    step_order           INTEGER      NOT NULL,
    step_name            VARCHAR(50)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    payload              TEXT,
    compensation_payload TEXT,
    error_message        VARCHAR(1000),
    created_at           TIMESTAMP(6) NOT NULL,
    updated_at           TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_saga_steps_instance FOREIGN KEY (saga_instance_id)
        REFERENCES saga_instances (id) ON DELETE CASCADE,
    CONSTRAINT uq_saga_instance_step UNIQUE (saga_instance_id, step_order)
);

CREATE INDEX idx_saga_instance_status ON saga_steps (saga_instance_id, status);
CREATE INDEX idx_saga_steps_pending ON saga_steps (status, step_order);

-- ---------------------------------------------------------------------------
-- Idempotency records (dual-layer idempotency DB guard, plan §6.1/§7)
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key  VARCHAR(128) NOT NULL,
    scope            VARCHAR(50)  NOT NULL,
    owner_id         BIGINT,
    status           VARCHAR(20)  NOT NULL,
    response_payload TEXT,
    created_at       TIMESTAMP(6) NOT NULL,
    expires_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_idempotency_scope_key UNIQUE (scope, idempotency_key)
);

CREATE INDEX idx_idempotency_expires ON idempotency_records (expires_at);

-- ---------------------------------------------------------------------------
-- PART 2: MONOLITH CORE TABLES
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- Orders (strangler: owned by the order service, but not yet extracted)
-- ---------------------------------------------------------------------------
-- order_category is the V63 STORED generated column: the entity maps it with
-- insertable=false/updatable=false and the database maintains it on every
-- status write. PG syntax: GENERATED ALWAYS AS (...) STORED.
CREATE TABLE orders (
    id                       BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_number             VARCHAR(50)  NOT NULL,
    customer_id              BIGINT       NOT NULL,
    restaurant_id            BIGINT       NOT NULL,
    delivery_address_id      BIGINT       NOT NULL,
    delivery_agent_id        BIGINT,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'PLACED',
    order_category           VARCHAR(20) GENERATED ALWAYS AS (
        CASE WHEN status IN ('SCHEDULED','PLACED','CONFIRMED','PREPARING','READY_FOR_PICKUP','OUT_FOR_DELIVERY') THEN 'LIVE'
             WHEN status = 'DELIVERED' THEN 'FULFILLED'
             ELSE 'CANCELLED' END
    ) STORED,
    subtotal                 DOUBLE PRECISION NOT NULL,
    delivery_fee             DOUBLE PRECISION,
    tax_amount               DOUBLE PRECISION,
    discount_amount          DOUBLE PRECISION,
    total_amount             DOUBLE PRECISION NOT NULL,
    loyalty_points_redeemed  INTEGER      NOT NULL DEFAULT 0,
    wallet_amount_used       DOUBLE PRECISION NOT NULL DEFAULT 0,
    tip_amount               DOUBLE PRECISION NOT NULL DEFAULT 0,
    coupon_id                BIGINT,
    special_instructions     VARCHAR(1000),
    contactless_delivery     BOOLEAN      DEFAULT FALSE,
    estimated_delivery_time  INTEGER,
    estimated_delivery_at    TIMESTAMP(6),
    scheduled_at             TIMESTAMP(6),
    live_eta_minutes         INTEGER,
    live_eta_at              TIMESTAMP(6),
    delivered_at             TIMESTAMP(6),
    cancellation_reason      VARCHAR(255),
    cancelled_by             VARCHAR(30),
    fulfillment_type         VARCHAR(20)  NOT NULL DEFAULT 'DELIVERY',
    device_id                VARCHAR(64),
    guest_phone              VARCHAR(15),
    gift_message             VARCHAR(500),
    recipient_name           VARCHAR(100),
    recipient_phone          VARCHAR(15),
    created_at               TIMESTAMP(6) NOT NULL,
    updated_at               TIMESTAMP(6),
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_orders_order_number UNIQUE (order_number)
);

-- ---------------------------------------------------------------------------
-- Menu items + element-collection join tables (restaurant service domain,
-- still owned by the monolith; minimal columns needed by the JPA entity).
-- Created here because order_items / cart_items reference menu_items.
-- ---------------------------------------------------------------------------
CREATE TABLE menu_items (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name                VARCHAR(200)  NOT NULL,
    description         VARCHAR(2000),
    category_id         BIGINT        NOT NULL,
    price               DOUBLE PRECISION NOT NULL,
    original_price      DOUBLE PRECISION,
    discount_percentage DOUBLE PRECISION,
    available           BOOLEAN       NOT NULL DEFAULT TRUE,
    food_type           VARCHAR(20)   NOT NULL,
    is_veg              BOOLEAN,
    is_spicy            BOOLEAN       DEFAULT FALSE,
    spice_level         VARCHAR(20),
    image_url           VARCHAR(500),
    preparation_time    INTEGER,
    bestseller          BOOLEAN       DEFAULT FALSE,
    recommended         BOOLEAN       DEFAULT FALSE,
    calories            INTEGER,
    serving_size        VARCHAR(50),
    average_rating      DOUBLE PRECISION DEFAULT 0.0,
    total_ratings       INTEGER       DEFAULT 0,
    stock_quantity      INTEGER,
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)
);

CREATE TABLE menu_item_tags (
    menu_item_id BIGINT NOT NULL,
    tag          VARCHAR(100) NOT NULL,
    PRIMARY KEY (menu_item_id, tag),
    CONSTRAINT fk_menu_item_tags_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);

CREATE TABLE menu_item_allergens (
    menu_item_id BIGINT NOT NULL,
    allergen     VARCHAR(100) NOT NULL,
    PRIMARY KEY (menu_item_id, allergen),
    CONSTRAINT fk_menu_item_allergens_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);

CREATE TABLE menu_item_ingredients (
    menu_item_id BIGINT NOT NULL,
    ingredient   VARCHAR(200) NOT NULL,
    PRIMARY KEY (menu_item_id, ingredient),
    CONSTRAINT fk_menu_item_ingredients_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);

CREATE TABLE menu_item_images (
    menu_item_id BIGINT NOT NULL,
    image_url    VARCHAR(500) NOT NULL,
    PRIMARY KEY (menu_item_id, image_url),
    CONSTRAINT fk_menu_item_images_item
        FOREIGN KEY (menu_item_id) REFERENCES menu_items (id) ON DELETE CASCADE
);

CREATE TABLE order_items (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id             BIGINT       NOT NULL,
    menu_item_id         BIGINT       NOT NULL,
    quantity             INTEGER      NOT NULL,
    price                DOUBLE PRECISION NOT NULL,
    special_instructions VARCHAR(500),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_item_menu FOREIGN KEY (menu_item_id) REFERENCES menu_items (id)
);

CREATE INDEX idx_order_item_order ON order_items (order_id);

CREATE TABLE order_item_customizations (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_item_id           BIGINT NOT NULL,
    customization_choice_id BIGINT NOT NULL,
    additional_price        DOUBLE PRECISION,
    CONSTRAINT fk_order_custom_item FOREIGN KEY (order_item_id) REFERENCES order_items (id)
);

-- ---------------------------------------------------------------------------
-- Carts (strangler: owned by the order service, but not yet extracted)
-- ---------------------------------------------------------------------------
CREATE TABLE carts (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    customer_id  BIGINT       NOT NULL,
    restaurant_id BIGINT,
    coupon_code  VARCHAR(50),
    updated_at   TIMESTAMP(6),
    CONSTRAINT uk_carts_customer_id UNIQUE (customer_id)
);

CREATE TABLE cart_items (
    id                   BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cart_id              BIGINT       NOT NULL,
    menu_item_id         BIGINT       NOT NULL,
    quantity             INTEGER      NOT NULL,
    special_instructions VARCHAR(500),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES carts (id),
    CONSTRAINT fk_cart_item_menu FOREIGN KEY (menu_item_id) REFERENCES menu_items (id)
);

CREATE INDEX idx_cart_item_cart ON cart_items (cart_id);

CREATE TABLE cart_item_customizations (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    cart_item_id            BIGINT NOT NULL,
    customization_choice_id BIGINT NOT NULL,
    CONSTRAINT fk_cart_custom_item FOREIGN KEY (cart_item_id) REFERENCES cart_items (id)
);

-- ---------------------------------------------------------------------------
-- Reviews (strangler: owned by the restaurant service, but not yet extracted)
-- ---------------------------------------------------------------------------
CREATE TABLE reviews (
    id                BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    customer_id       BIGINT       NOT NULL,
    restaurant_id     BIGINT       NOT NULL,
    order_id          BIGINT       NOT NULL,
    rating            INTEGER      NOT NULL,
    comment           VARCHAR(2000),
    food_rating       INTEGER,
    delivery_rating   INTEGER,
    moderation_status VARCHAR(20)  NOT NULL DEFAULT 'APPROVED',
    owner_response    TEXT,
    created_at        TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_reviews_order_id UNIQUE (order_id),
    CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_review_customer ON reviews (customer_id);
CREATE INDEX idx_review_restaurant ON reviews (restaurant_id);

-- Element-collection join table for Review.images (@ElementCollection)
CREATE TABLE review_images (
    review_id BIGINT NOT NULL,
    images    VARCHAR(500) NOT NULL,
    PRIMARY KEY (review_id, images),
    CONSTRAINT fk_review_images_review
        FOREIGN KEY (review_id) REFERENCES reviews (id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------------
-- Order support tables: timeline, ETA snapshots, GST invoices
-- ---------------------------------------------------------------------------
CREATE TABLE order_timeline_events (
    id         BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id   BIGINT       NOT NULL,
    event_type VARCHAR(50)  NOT NULL,
    status     VARCHAR(30),
    message    VARCHAR(500),
    actor_id   BIGINT,
    actor_role VARCHAR(30),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_timeline_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_timeline_order ON order_timeline_events (order_id, created_at);

CREATE TABLE order_eta_snapshots (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id                BIGINT       NOT NULL,
    eta_minutes             INTEGER      NOT NULL,
    eta_at                  TIMESTAMP(6) NOT NULL,
    confidence_low_minutes  INTEGER,
    confidence_high_minutes INTEGER,
    traffic_factor          DOUBLE PRECISION,
    surge_multiplier        DOUBLE PRECISION,
    factors_summary         VARCHAR(500),
    recorded_at             TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_eta_snapshot_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE INDEX idx_eta_snapshot_order ON order_eta_snapshots (order_id, recorded_at);

CREATE TABLE order_invoices (
    id               BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id         BIGINT       NOT NULL,
    invoice_number   VARCHAR(40)  NOT NULL,
    subtotal         DOUBLE PRECISION NOT NULL,
    delivery_fee     DOUBLE PRECISION NOT NULL DEFAULT 0,
    tax_amount       DOUBLE PRECISION NOT NULL DEFAULT 0,
    cgst_amount      DOUBLE PRECISION NOT NULL DEFAULT 0,
    sgst_amount      DOUBLE PRECISION NOT NULL DEFAULT 0,
    discount_amount  DOUBLE PRECISION NOT NULL DEFAULT 0,
    total_amount     DOUBLE PRECISION NOT NULL,
    restaurant_gstin VARCHAR(20),
    issued_at        TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    pdf_storage_key  VARCHAR(512),
    pdf_generated_at TIMESTAMP(6),
    emailed_at       TIMESTAMP(6),
    email_recipient  VARCHAR(255),
    email_attempts   INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT uk_invoices_order_id UNIQUE (order_id),
    CONSTRAINT uk_invoices_invoice_number UNIQUE (invoice_number),
    CONSTRAINT fk_invoice_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

-- ---------------------------------------------------------------------------
-- Payments (order_id nullable since V4: wallet top-ups carry no order)
-- ---------------------------------------------------------------------------
CREATE TABLE payments (
    id                      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    order_id                BIGINT,
    customer_id             BIGINT,
    purpose                 VARCHAR(30)  NOT NULL DEFAULT 'ORDER',
    payment_method          VARCHAR(20)  NOT NULL,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    amount                  DOUBLE PRECISION NOT NULL,
    wallet_amount           DOUBLE PRECISION NOT NULL DEFAULT 0,
    gateway_amount          DOUBLE PRECISION NOT NULL DEFAULT 0,
    transaction_id          VARCHAR(100),
    gateway_order_id        VARCHAR(100),
    gateway_payment_id      VARCHAR(100),
    idempotency_key         VARCHAR(128),
    payment_gateway_response VARCHAR(2000),
    created_at              TIMESTAMP(6) NOT NULL,
    completed_at            TIMESTAMP(6),
    CONSTRAINT uk_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

-- ---------------------------------------------------------------------------
-- PART 3: KEY QUERY INDEXES
-- ---------------------------------------------------------------------------
-- The monolith's hottest query paths, ported from the MySQL index set
-- (V60/V61 covering indexes + V63 category-pruned composites). Standard
-- CREATE INDEX (no CONCURRENTLY): Flyway wraps migrations in a transaction.
-- Note: outbox_events(status, created_at) is covered by idx_outbox_status_created
-- (PART 1) and idempotency_records(scope, idempotency_key) by the
-- uk_idempotency_scope_key unique constraint, which PG backs with its own index.

-- orders(customer_id, created_at DESC) — customer order-history listing
CREATE INDEX idx_order_customer_created ON orders (customer_id, created_at DESC);

-- orders(restaurant_id, status, created_at DESC) — restaurant queue + kitchen
CREATE INDEX idx_order_restaurant_status_created
    ON orders (restaurant_id, status, created_at DESC);

-- orders(delivery_agent_id, status) — agent assignment screen
CREATE INDEX idx_order_agent_status ON orders (delivery_agent_id, status);

-- orders(status, created_at) — status dashboards, kitchen queue
CREATE INDEX idx_order_status_created ON orders (status, created_at);

-- menu_items(category_id, available) — menu browse by category
CREATE INDEX idx_menu_item_category_available ON menu_items (category_id, available);

-- payments(order_id, status) — payment lookup per order
CREATE INDEX idx_payment_order_status ON payments (order_id, status);

-- reviews(restaurant_id, moderation_status, created_at) — public feed + moderation
CREATE INDEX idx_review_restaurant_moderation_created
    ON reviews (restaurant_id, moderation_status, created_at);

-- Full-text search (replaces MySQL FULLTEXT): GIN over tsvector expressions.
-- Menu items: search by name + description (restaurants index lives in V2
-- because the restaurants table is created there).
CREATE INDEX ft_menu_item_search_gin ON menu_items USING GIN (to_tsvector('english', name || ' ' || coalesce(description, '')));
