-- V32__audit_events.sql
-- Immutable audit trail for sensitive operations (FEATURE #2).
--
-- audit_events is append-only: nothing in the application updates or deletes a row.
-- Each row captures the actor, action, target resource and before/after state so
-- that sensitive operations (refunds, logins, admin state changes) can be reviewed.
--
-- The table and both indexes are created idempotently (information_schema guards +
-- prepared statements) so the migration is safe on databases where they may already
-- exist from a manual hotfix.

SET @schema_name = DATABASE();

-- audit_events
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'audit_events') = 0,
    'CREATE TABLE audit_events (
        id BIGINT NOT NULL AUTO_INCREMENT,
        actor_id BIGINT NULL,
        actor_role VARCHAR(30) NULL,
        action VARCHAR(80) NOT NULL,
        resource_type VARCHAR(80) NOT NULL,
        resource_id VARCHAR(100) NULL,
        old_state TEXT NULL,
        new_state TEXT NULL,
        ip_address VARCHAR(45) NULL,
        trace_id VARCHAR(64) NULL,
        request_id VARCHAR(64) NULL,
        created_at DATETIME(6) NOT NULL,
        PRIMARY KEY (id),
        INDEX idx_audit_action_created (action, created_at),
        INDEX idx_audit_resource (resource_type, resource_id)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_audit_action_created (guarded in case the table pre-existed without indexes)
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'audit_events'
       AND INDEX_NAME = 'idx_audit_action_created') = 0,
    'ALTER TABLE audit_events ADD INDEX idx_audit_action_created (action, created_at)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- idx_audit_resource
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'audit_events'
       AND INDEX_NAME = 'idx_audit_resource') = 0,
    'ALTER TABLE audit_events ADD INDEX idx_audit_resource (resource_type, resource_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
