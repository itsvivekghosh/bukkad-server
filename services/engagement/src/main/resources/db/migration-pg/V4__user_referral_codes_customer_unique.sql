-- W1-LOYALTY (feature #4 / ADR-005): one referral row per customer.
--
-- The referral service assumes a single user_referral_codes row per customer
-- (findByCustomerId returns one row; apply binds that row). Without a unique
-- constraint, concurrent first applies for the same customer each passed the
-- code-exists guard (every worker draws its own code) and created duplicate
-- rows, letting several workers bind the same referrer and violating the
-- partial unique index uq_user_referral_codes_referred_by (W1-LOYALTY V10).
-- The unique index makes the first insert win and — combined with
-- ON CONFLICT (customer_id) DO NOTHING on insertReferralRow — every racing
-- duplicate becomes a benign 0-row insert that re-reads the winner's row.
-- Harmless for existing data: the service has always re-read by customer_id
-- and treated the first match as authoritative.

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_referral_codes_customer_id
    ON public.user_referral_codes (customer_id);
