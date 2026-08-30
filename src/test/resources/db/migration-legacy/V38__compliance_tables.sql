-- V38__compliance_tables.sql
-- DPDP/GDPR support tables (FEATURE #16).
--
-- consent_records: one row per (user, purpose) tracking the latest grant/revoke of
--   marketing / notification consent. Upserted by ConsentService.
-- data_export_requests: audit trail + payload store for "export my data" requests.
--
-- Both tables are created idempotently (information_schema guards + prepared
-- statements), matching the pattern used by V32__audit_events.

SET @schema_name = DATABASE();

-- consent_records
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'consent_records') = 0,
    'CREATE TABLE consent_records (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        purpose VARCHAR(50) NOT NULL,
        granted TINYINT(1) NOT NULL,
        source VARCHAR(20) NULL,
        created_at DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        UNIQUE KEY uk_consent_user_purpose (user_id, purpose),
        INDEX idx_consent_user (user_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- data_export_requests
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'data_export_requests') = 0,
    'CREATE TABLE data_export_requests (
        id BIGINT NOT NULL AUTO_INCREMENT,
        user_id BIGINT NOT NULL,
        requested_at DATETIME(6) NOT NULL,
        status VARCHAR(20) NOT NULL,
        payload_json TEXT NULL,
        completed_at DATETIME(6) NULL,
        PRIMARY KEY (id),
        INDEX idx_export_user_status (user_id, status, completed_at),
        INDEX idx_export_requested_at (requested_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
