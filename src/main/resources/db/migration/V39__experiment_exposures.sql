-- V39__experiment_exposures.sql
-- A/B experiment exposure log (FEATURE #7).
--
-- One row per (experiment, user) — the first time a user was assigned a variant.
-- The unique constraint makes the table idempotent under concurrent assignment
-- and doubles as the cohort census for lift analysis.

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'experiment_exposures') = 0,
    'CREATE TABLE experiment_exposures (
        id BIGINT NOT NULL AUTO_INCREMENT,
        experiment_key VARCHAR(80) NOT NULL,
        user_id BIGINT NOT NULL,
        variant VARCHAR(80) NOT NULL,
        bucket INT NOT NULL,
        exposed_at DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_experiment_user (experiment_key, user_id),
        INDEX idx_experiment_variant (experiment_key, variant),
        INDEX idx_experiment_exposed_at (exposed_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
