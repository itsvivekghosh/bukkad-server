-- =============================================================================
-- V55: Event-driven materialized summary tables (Phase 2).
--
-- `trending_dishes` replaces the cross-domain aggregate query
-- (OrderItemRepository.findTrendingByCreatedSince: order_items JOIN menu_items)
-- with a summary table owned by the ANALYTICS domain, fed by the
-- ORDER_ITEMS_SNAPSHOT outbox event. The ADMIN/ANALYTICS domain never joins
-- ORDER tables directly.
-- =============================================================================

CREATE TABLE IF NOT EXISTS trending_dishes (
    menu_item_id      BIGINT       NOT NULL,
    restaurant_id     BIGINT       NOT NULL,
    dish_name         VARCHAR(255) NOT NULL,
    quantity_sold     BIGINT       NOT NULL DEFAULT 0,
    last_order_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (menu_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Hot read path for the home feed: rank by quantity sold within the window.
-- Guarded via information_schema so the migration is idempotent (MySQL has no
-- CREATE INDEX IF NOT EXISTS).
SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'trending_dishes'
      AND index_name = 'idx_trending_dishes_qty');
SET @ddl := IF(@index_exists = 0,
    'CREATE INDEX idx_trending_dishes_qty ON trending_dishes (quantity_sold DESC)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- `orders_archive` — partitioned by RANGE COLUMNS on created_at for
-- retention/archival.
--
-- MySQL 8 partitioned InnoDB tables cannot carry foreign keys, so the live
-- `orders` table keeps its FKs and old rows are MOVED here by the archive job
-- (OrderArchiveService). Each partition covers one quarter, which makes the
-- oldest partition DROP cheap (partition pruning) instead of a bulk DELETE.
-- RANGE COLUMNS is used (not TO_DAYS()) because TO_DAYS() is a timezone-
-- dependent function and is rejected by MySQL 8+ partitioning.
-- =============================================================================

CREATE TABLE IF NOT EXISTS orders_archive (
    id                    BIGINT       NOT NULL,
    order_number          VARCHAR(32)  NOT NULL,
    customer_id           BIGINT       NOT NULL,
    restaurant_id         BIGINT       NOT NULL,
    status                VARCHAR(32)  NULL,
    total_amount          DECIMAL(10,2) NULL,
    delivery_address_id   BIGINT       NULL,
    special_instructions  VARCHAR(500) NULL,
    created_at            DATETIME     NOT NULL,
    updated_at            DATETIME     NULL,
    delivered_at          DATETIME     NULL,
    estimated_delivery_at DATETIME     NULL,
    archived_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
PARTITION BY RANGE COLUMNS (created_at) (
    PARTITION p2024q1 VALUES LESS THAN ('2024-04-01 00:00:00'),
    PARTITION p2024q2 VALUES LESS THAN ('2024-07-01 00:00:00'),
    PARTITION p2024q3 VALUES LESS THAN ('2024-10-01 00:00:00'),
    PARTITION p2024q4 VALUES LESS THAN ('2025-01-01 00:00:00'),
    PARTITION p2025q1 VALUES LESS THAN ('2025-04-01 00:00:00'),
    PARTITION p2025q2 VALUES LESS THAN ('2025-07-01 00:00:00'),
    PARTITION p2025q3 VALUES LESS THAN ('2025-10-01 00:00:00'),
    PARTITION p2025q4 VALUES LESS THAN ('2026-01-01 00:00:00'),
    PARTITION p2026q1 VALUES LESS THAN ('2026-04-01 00:00:00'),
    PARTITION p_future   VALUES LESS THAN (MAXVALUE)
);

SET @idx1 := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'orders_archive'
      AND index_name = 'idx_orders_archive_customer');
SET @ddl1 := IF(@idx1 = 0,
    'CREATE INDEX idx_orders_archive_customer ON orders_archive (customer_id)',
    'SELECT 1');
PREPARE stmt1 FROM @ddl1; EXECUTE stmt1; DEALLOCATE PREPARE stmt1;

SET @idx2 := (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'orders_archive'
      AND index_name = 'idx_orders_archive_status');
SET @ddl2 := IF(@idx2 = 0,
    'CREATE INDEX idx_orders_archive_status ON orders_archive (status)',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2; EXECUTE stmt2; DEALLOCATE PREPARE stmt2;
