-- V51__saga_events.sql
-- Creates tables for saga pattern implementation: saga_instances and saga_steps
-- to manage long-running transactions like order -> payment -> settlement.

SET @schema_name = DATABASE();

-- saga_instances: tracks the overall saga state
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'saga_instances') = 0,
    'CREATE TABLE saga_instances (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        saga_type VARCHAR(50) NOT NULL,
        saga_id VARCHAR(100) NOT NULL UNIQUE,
        current_step VARCHAR(50),
        status VARCHAR(20) NOT NULL, -- STARTED, STEP_COMPLETED, COMPLETED, COMPENSATING, COMPENSATED, FAILED
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        payload JSON, -- serialized input data for the saga
        INDEX idx_saga_type_status (saga_type, status),
        INDEX idx_saga_id (saga_id)
    )',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- saga_steps: tracks each step within a saga, including compensation info
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'saga_steps') = 0,
    'CREATE TABLE saga_steps (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        saga_instance_id BIGINT NOT NULL,
        step_order INT NOT NULL,
        step_name VARCHAR(50) NOT NULL,
        status VARCHAR(20) NOT NULL, -- PENDING, COMPLETED, FAILED, COMPENSATED
        payload JSON, -- input/output data for this step
        compensation_payload JSON, -- data needed to compensate this step
        error_message VARCHAR(1000),
        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
        FOREIGN KEY (saga_instance_id) REFERENCES saga_instances(id) ON DELETE CASCADE,
        UNIQUE KEY uq_saga_instance_step (saga_instance_id, step_order),
        INDEX idx_saga_instance_status (saga_instance_id, status)
    )',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Index for querying pending steps for processing
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'saga_steps' AND INDEX_NAME = 'idx_saga_steps_pending') = 0,
    'CREATE INDEX idx_saga_steps_pending ON saga_steps(status, step_order)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;