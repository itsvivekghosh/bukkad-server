-- V40__churn_scores.sql
-- Customer churn scoring (FEATURE #12).
--
-- One row per customer holding the latest weekly score, the factors behind it and
-- whether a retention outreach has already been dispatched for the current
-- high-risk episode (prevents repeat spam).

SET @schema_name = DATABASE();

SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'churn_scores') = 0,
    'CREATE TABLE churn_scores (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        score INT NOT NULL,
        risk_level VARCHAR(10) NOT NULL,
        factors TEXT NULL,
        scored_at DATETIME(6) NOT NULL,
        retention_action_taken TINYINT(1) NOT NULL DEFAULT 0,
        PRIMARY KEY (id),
        UNIQUE KEY uk_churn_user (user_id),
        INDEX idx_churn_score (score),
        INDEX idx_churn_risk_scored (risk_level, scored_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
