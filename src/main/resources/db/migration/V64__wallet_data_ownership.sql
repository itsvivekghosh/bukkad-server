-- ============================================================================
-- V64: Wallet data ownership (Phase 2 — database-per-service preparation)
-- ----------------------------------------------------------------------------
-- The wallet domain now owns its balance state instead of renting a column on
-- the identity domain's customers table:
--
--   * wallet_balances: one row per customer (customer_id UNIQUE), holding the
--     authoritative balance. The pessimistic write lock moves here, so
--     concurrent debits/credits serialise on the WALLET domain's own row —
--     no identity-row contention, and no Customer entity in wallet code.
--   * customers.wallet_balance is kept in sync through the
--     identity.api.CustomerWalletSyncPort inside the same transaction during
--     the transition (readers across pricing/profile/export stay untouched).
--
-- Backfill seeds every customer's current balance exactly once (guarded by
-- the UNIQUE key + NOT EXISTS, so re-running is a no-op).
-- ============================================================================

SET @schema_name = DATABASE();

CREATE TABLE IF NOT EXISTS wallet_balances (
    id BIGINT NOT NULL AUTO_INCREMENT,
    customer_id BIGINT NOT NULL,
    balance DECIMAL(12, 2) NOT NULL DEFAULT 0.00,
    updated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wallet_balance_customer (customer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Backfill from the identity-owned column (transition source of truth)
INSERT INTO wallet_balances (customer_id, balance, updated_at)
SELECT c.id, COALESCE(c.wallet_balance, 0), NOW()
FROM customers c
WHERE NOT EXISTS (SELECT 1 FROM wallet_balances w WHERE w.customer_id = c.id);
