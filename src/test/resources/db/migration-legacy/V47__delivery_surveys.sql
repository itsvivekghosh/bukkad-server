-- V47__delivery_surveys.sql
-- Adds the post-delivery satisfaction survey table.
--
-- A customer can submit one survey per delivered order; responses feed
-- restaurant analytics (average delivery/food/speed ratings).
--
--   order_id        : unique per order (one survey per order)
--   rating_delivery : 1-5, NULL when the customer skipped the question
--   rating_food     : 1-5, NULL when the customer skipped the question
--   rating_speed    : 1-5, NULL when the customer skipped the question
--   comment         : optional free-text feedback
--
-- The table is created with CREATE TABLE IF NOT EXISTS so the migration is
-- idempotent and safe on databases where it may already exist from a manual
-- hotfix (same convention as V42 gift_orders / V43 group_orders).

CREATE TABLE IF NOT EXISTS delivery_surveys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    order_id        BIGINT       NOT NULL,
    customer_id     BIGINT       NOT NULL,
    rating_delivery INT          NULL,
    rating_food     INT          NULL,
    rating_speed    INT          NULL,
    comment         VARCHAR(1000) NULL,
    submitted_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_survey_order (order_id),
    CONSTRAINT fk_survey_order    FOREIGN KEY (order_id)    REFERENCES orders(id)    ON DELETE CASCADE,
    CONSTRAINT fk_survey_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    INDEX idx_survey_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optional: speeds up per-restaurant average aggregation (surveys joined to
-- their orders). Guarded so re-runs never fail.
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'delivery_surveys'
      AND INDEX_NAME = 'idx_survey_restaurant_lookup'
);
SET @sql = IF(
    @idx_exists = 0,
    'ALTER TABLE delivery_surveys ADD INDEX idx_survey_restaurant_lookup (order_id, customer_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
