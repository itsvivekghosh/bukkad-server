-- V49__delivery_operations.sql
-- Delivery operations support for three features:
--   1. Agent shifts   : agent_shifts tracks rider working shifts (start/end)
--   2. Incentives     : rider_earnings.bonus_amount / bonus_reason
--   3. COD cash       : agent_cod_wallets tracks cash collected vs deposited
--
-- Both tables are created with CREATE TABLE IF NOT EXISTS and the
-- rider_earnings columns are added idempotently (information_schema guards +
-- prepared statements, V31 style) so the migration is safe on databases where
-- the objects may already exist from a manual hotfix.

SET @schema_name = DATABASE();

-- agent_shifts: one row per rider shift; end_time is populated when the shift
-- is completed (a started shift keeps start_time as a placeholder value).
CREATE TABLE IF NOT EXISTS agent_shifts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    agent_id   BIGINT      NOT NULL,
    shift_date DATE        NOT NULL,
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_shift_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id) ON DELETE CASCADE,
    INDEX idx_shift_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Guarded re-add for the rare case the table pre-existed without the index.
SET @idx_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @schema_name
      AND TABLE_NAME = 'agent_shifts'
      AND INDEX_NAME = 'idx_shift_agent'
);
SET @sql = IF(
    @idx_exists = 0,
    'ALTER TABLE agent_shifts ADD INDEX idx_shift_agent (agent_id)',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- rider_earnings.bonus_amount
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND COLUMN_NAME = 'bonus_amount') = 0,
    'ALTER TABLE rider_earnings ADD COLUMN bonus_amount DOUBLE DEFAULT 0.0',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- rider_earnings.bonus_reason
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'rider_earnings' AND COLUMN_NAME = 'bonus_reason') = 0,
    'ALTER TABLE rider_earnings ADD COLUMN bonus_reason VARCHAR(100) NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- agent_cod_wallets: one wallet per agent; cash-on-delivery totals and the
-- last reconciliation timestamp.
CREATE TABLE IF NOT EXISTS agent_cod_wallets (
    id                   BIGINT      NOT NULL AUTO_INCREMENT,
    agent_id             BIGINT      NOT NULL,
    total_cash_collected DOUBLE      NOT NULL DEFAULT 0.0,
    total_cash_deposited DOUBLE      NOT NULL DEFAULT 0.0,
    last_reconciled_at   DATETIME(6) NULL,
    created_at           DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_cod_wallet_agent (agent_id),
    CONSTRAINT fk_agent_cod_wallet_agent FOREIGN KEY (agent_id) REFERENCES delivery_agents(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
