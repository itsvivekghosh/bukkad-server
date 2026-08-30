-- === V27__missing_entity_tables.sql ===
-- Tables referenced by JPA entities that were never created by any earlier
-- migration. The consolidated V1__baseline_schema.sql was assembled from the
-- original V1–V26 series and dropped these tables during the merge.
--
-- All statements are idempotent (`IF NOT EXISTS`) so this migration is safe on
-- existing environments where the tables may already exist (e.g. created by
-- manual DDL or the short-lived V26 async-eventing file):
--   * cities                     -> City entity
--   * group_orders               -> GroupOrder entity
--   * group_order_participants   -> GroupOrder @ElementCollection table
--   * dead_letter_events         -> DeadLetterEvent entity (outbox DLQ)
--   * restaurant_ratings_summary -> MaterializedViewRefreshService target
--   * restaurant_order_stats     -> MaterializedViewRefreshService target

-- ── Cities ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS cities (
    id                         BIGINT NOT NULL AUTO_INCREMENT,
    name                       VARCHAR(100) NOT NULL,
    country                    VARCHAR(100),
    currency                   VARCHAR(50),
    timezone                   VARCHAR(50),
    supported_payment_methods  VARCHAR(50),
    default_min_order_amount   DOUBLE,
    is_serviceable             BIT(1) DEFAULT 0,
    is_active                  BIT(1) DEFAULT 0,
    created_at                 DATETIME(6) NOT NULL,
    updated_at                 DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Group Orders ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS group_orders (
    id                       BIGINT NOT NULL AUTO_INCREMENT,
    order_number             VARCHAR(255) NOT NULL,
    restaurant_id            BIGINT NOT NULL,
    primary_customer_id      BIGINT NOT NULL,
    status                   VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    subtotal                 DOUBLE,
    delivery_fee             DOUBLE,
    tax_amount               DOUBLE,
    discount_amount          DOUBLE,
    total_amount             DOUBLE,
    tip_amount               DOUBLE,
    special_instructions     VARCHAR(255),
    contactless_delivery     BIT(1) DEFAULT 0,
    created_at               DATETIME(6) NOT NULL,
    confirmed_at             DATETIME(6),
    cancelled_at             DATETIME(6),
    cancellation_reason      VARCHAR(255),
    updated_at               DATETIME(6) NOT NULL,
    payment_method           VARCHAR(30),
    loyalty_points_redeemed  INT DEFAULT 0,
    wallet_amount_used       DOUBLE DEFAULT 0.0,
    PRIMARY KEY (id),
    CONSTRAINT fk_group_order_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id),
    CONSTRAINT fk_group_order_customer FOREIGN KEY (primary_customer_id) REFERENCES customers(id),
    INDEX idx_group_order_status (status),
    INDEX idx_group_order_created (created_at),
    INDEX idx_group_order_restaurant_status (restaurant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Group order participants (GroupOrder @ElementCollection)
CREATE TABLE IF NOT EXISTS group_order_participants (
    group_order_id BIGINT NOT NULL,
    customer_id    BIGINT NOT NULL,
    PRIMARY KEY (group_order_id, customer_id),
    CONSTRAINT fk_gop_group_order FOREIGN KEY (group_order_id) REFERENCES group_orders(id) ON DELETE CASCADE,
    INDEX idx_gop_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Dead letter queue (outbox failures) ───────────────────────────────────
CREATE TABLE IF NOT EXISTS dead_letter_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_type VARCHAR(80) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    payload TEXT NOT NULL,
    last_error VARCHAR(1000),
    retry_count INT NOT NULL DEFAULT 0,
    source VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL,
    requeued_at DATETIME(6),
    PRIMARY KEY (id),
    INDEX idx_dlq_status_created (status, created_at),
    INDEX idx_dlq_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Materialized-view summary tables (MaterializedViewRefreshService) ─────
-- Refreshed on a schedule by MaterializedViewRefreshService; referenced via
-- native INSERT ... ON DUPLICATE KEY UPDATE, so they must exist on fresh DBs.
-- Idempotent: existing environments that created them manually keep working.
CREATE TABLE IF NOT EXISTS restaurant_ratings_summary (
    restaurant_id      BIGINT NOT NULL,
    average_rating     DOUBLE NOT NULL DEFAULT 0,
    total_reviews      INT NOT NULL DEFAULT 0,
    positive_reviews   INT NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurant_id),
    CONSTRAINT fk_rrs_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS restaurant_order_stats (
    restaurant_id      BIGINT NOT NULL,
    total_orders       INT NOT NULL DEFAULT 0,
    delivered_orders   INT NOT NULL DEFAULT 0,
    cancelled_orders   INT NOT NULL DEFAULT 0,
    total_revenue      DOUBLE NOT NULL DEFAULT 0,
    avg_order_value    DOUBLE NOT NULL DEFAULT 0,
    last_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (restaurant_id),
    CONSTRAINT fk_ros_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
