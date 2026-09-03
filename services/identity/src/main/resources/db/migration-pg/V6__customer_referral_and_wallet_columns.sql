-- Batch 6 (migration-batch-6-identity): extend the identity-owned
-- `customers` table with the columns required by the extracted
-- ReferralService + CustomerWalletSyncAdapter (referral code, referrer FK,
-- wallet balance mirror).
--
-- The identity service is the authoritative owner of the customers table
-- from batch 5 onward; the monolith continues to operate on its own
-- `bhukkad.customers` row through working-copy adapters until wallet +
-- membership + referral extraction completes.

ALTER TABLE customers
    ADD COLUMN referral_code  VARCHAR(32),
    ADD COLUMN referred_by_id BIGINT REFERENCES customers (id),
    ADD COLUMN wallet_balance DOUBLE PRECISION NOT NULL DEFAULT 0.0;

CREATE UNIQUE INDEX uk_customer_referral_code
    ON customers (referral_code)
    WHERE referral_code IS NOT NULL;

CREATE INDEX idx_customer_referred_by_id
    ON customers (referred_by_id);