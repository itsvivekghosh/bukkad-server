-- Identity service V7 (Batch 6 WAVE 2): bring membership + affiliate tables
-- from the slim WAVE 1 shape (V4) to the full monolith shape ported by the
-- rich entities. Column names match the monolith entities; slim-only columns
-- are backfilled and dropped.
--
-- Note on data: the WAVE 1 slim tables were scaffolding with no reward
-- semantics (affiliate_referrals tracked only code strings, no customer FK),
-- so affiliate_referrals rows are dropped rather than half-migrated;
-- membership/plan/code rows are preserved.

-- ---------------------------------------------------------------------------
-- membership_plans: monolith shape
-- ---------------------------------------------------------------------------
ALTER TABLE membership_plans
    ADD COLUMN description TEXT,
    ADD COLUMN price_per_month DOUBLE PRECISION,
    ADD COLUMN free_delivery BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN discount_percent DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN is_active BOOLEAN,
    ADD COLUMN tier_level INTEGER DEFAULT 0,
    ADD COLUMN max_discount_percent DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN referral_bonus_percent DOUBLE PRECISION DEFAULT 0.0,
    ADD COLUMN referral_max_per_month INTEGER DEFAULT 0;

UPDATE membership_plans
SET price_per_month = price,
    is_active = active;

ALTER TABLE membership_plans
    ALTER COLUMN price_per_month SET NOT NULL,
    ALTER COLUMN is_active SET NOT NULL,
    ALTER COLUMN is_active SET DEFAULT true,
    ALTER COLUMN tier_level SET DEFAULT 0,
    ALTER COLUMN max_discount_percent SET DEFAULT 0.0,
    ALTER COLUMN referral_bonus_percent SET DEFAULT 0.0,
    ALTER COLUMN referral_max_per_month SET DEFAULT 0;

ALTER TABLE membership_plans
    DROP COLUMN tier,
    DROP COLUMN price,
    DROP COLUMN active;

-- ---------------------------------------------------------------------------
-- customer_memberships: monolith validity columns (starts_at/ends_at)
-- ---------------------------------------------------------------------------
ALTER TABLE customer_memberships
    ADD COLUMN starts_at TIMESTAMP(6),
    ADD COLUMN ends_at TIMESTAMP(6);

UPDATE customer_memberships
SET starts_at = started_at,
    ends_at = expires_at;

ALTER TABLE customer_memberships
    ALTER COLUMN starts_at SET NOT NULL,
    ALTER COLUMN ends_at SET NOT NULL;

ALTER TABLE customer_memberships
    DROP COLUMN started_at,
    DROP COLUMN expires_at;

-- The WAVE 1 FK referenced users(id); the rich entity links the customers
-- aggregate, so re-point the FK at customers(id).
ALTER TABLE customer_memberships DROP CONSTRAINT customer_memberships_customer_id_fkey;
ALTER TABLE customer_memberships
    ADD CONSTRAINT fk_membership_customer FOREIGN KEY (customer_id) REFERENCES customers (id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- affiliate_codes: monolith shape (code/channel/reward registry)
-- ---------------------------------------------------------------------------
ALTER TABLE affiliate_codes
    ADD COLUMN name VARCHAR(120),
    ADD COLUMN channel VARCHAR(40),
    ADD COLUMN reward_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    ADD COLUMN is_active BOOLEAN;

UPDATE affiliate_codes
SET name = code,
    is_active = active;

ALTER TABLE affiliate_codes
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN is_active SET DEFAULT true;

ALTER TABLE affiliate_codes
    DROP COLUMN restaurant_id,
    DROP COLUMN commission_pct,
    DROP COLUMN active;

-- ---------------------------------------------------------------------------
-- affiliate_referrals: monolith FK shape (code + customer + reward ledger).
-- Slim WAVE 1 rows carried no customer FK or reward semantics; dropped.
-- ---------------------------------------------------------------------------
DELETE FROM affiliate_referrals;

ALTER TABLE affiliate_referrals
    ADD COLUMN affiliate_code_id BIGINT,
    ADD COLUMN customer_id BIGINT,
    ADD COLUMN reward_amount DOUBLE PRECISION NOT NULL DEFAULT 0.0;

UPDATE affiliate_referrals r
SET affiliate_code_id = c.id
FROM affiliate_codes c
WHERE r.affiliate_code = c.code;

ALTER TABLE affiliate_referrals
    ALTER COLUMN affiliate_code_id SET NOT NULL,
    ALTER COLUMN customer_id SET NOT NULL;

ALTER TABLE affiliate_referrals
    DROP COLUMN affiliate_code,
    DROP COLUMN referred_by,
    DROP COLUMN referred_user;

ALTER TABLE affiliate_referrals
    ADD CONSTRAINT fk_affiliate_referral_code FOREIGN KEY (affiliate_code_id) REFERENCES affiliate_codes (id),
    ADD CONSTRAINT fk_affiliate_referral_customer FOREIGN KEY (customer_id) REFERENCES customers (id);

CREATE INDEX idx_affiliate_referral_code ON affiliate_referrals (affiliate_code_id);
CREATE INDEX idx_affiliate_referral_status ON affiliate_referrals (status);
