-- Audit C-5/H-5 (schema drift): the payment entities map columns the LEGACY V1
-- baseline lacks, so production (flyway + ddl-auto=none) fails at runtime while
-- tests on fresh schemas pass. Additive-only repair, PostgreSQL dialect.
--
--   AgentCodWallet        → agent_cod_wallets.balance / updated_at missing
--   RiderEarning          → rider_earnings.amount stored as double precision
--   AgentCodWallet/RiderEarning consumers (internal delivery controllers and
--   the order-saga's RiderWalletClient) also expect the status columns below.

-- Agent COD wallet: live balance of undeposited cash plus audit timestamps.
-- balance is seeded from the legacy collected-minus-deposited model so
-- existing wallets keep reconciling after the switchover.
ALTER TABLE public.agent_cod_wallets
    ADD COLUMN IF NOT EXISTS balance numeric(12,2) DEFAULT 0.00 NOT NULL,
    ADD COLUMN IF NOT EXISTS status character varying(32) DEFAULT 'ACTIVE' NOT NULL,
    ADD COLUMN IF NOT EXISTS updated_at timestamp(6) without time zone DEFAULT localtimestamp(6) NOT NULL;

UPDATE public.agent_cod_wallets
   SET balance = total_cash_collected - total_cash_deposited
 WHERE balance = 0.00
   AND total_cash_collected - total_cash_deposited <> 0;

-- Rider earnings: defensive add of the lifecycle columns (older legacy
-- variants lack them; V1 already carries status/paid_at, which stay as-is)
-- and the money-precision fix double precision → numeric(12,2).
ALTER TABLE public.rider_earnings
    ADD COLUMN IF NOT EXISTS status character varying(32) DEFAULT 'EARNED' NOT NULL,
    ADD COLUMN IF NOT EXISTS paid_at timestamp without time zone;

ALTER TABLE public.rider_earnings
    ALTER COLUMN amount TYPE numeric(12,2) USING amount::numeric(12,2);

-- Exactly-one-payable-earning-per-order, matching the application-level
-- (agentId, orderId) dedup in DeliveryPaymentController.recordEarning.
CREATE UNIQUE INDEX IF NOT EXISTS uq_rider_earnings_agent_order
    ON public.rider_earnings (agent_id, order_id);

-- V1 dropped the IDENTITY sequences and primary keys these bigint id columns
-- depend on (AgentCodWallet/RiderEarning use GenerationType.IDENTITY): inserts
-- failed with "null value in column id violates not-null constraint" on every
-- production write. Guarded so re-application stays a no-op once present.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'agent_cod_wallets'
          AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.agent_cod_wallets
            ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                SEQUENCE NAME public.agent_cod_wallets_id_seq
                START WITH 1
                INCREMENT BY 1
                NO MINVALUE
                NO MAXVALUE
                CACHE 1
            );
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'agent_cod_wallets_pkey') THEN
        ALTER TABLE ONLY public.agent_cod_wallets
            ADD CONSTRAINT agent_cod_wallets_pkey PRIMARY KEY (id);
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'rider_earnings'
          AND column_name = 'id' AND is_identity = 'YES') THEN
        ALTER TABLE public.rider_earnings
            ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
                SEQUENCE NAME public.rider_earnings_id_seq
                START WITH 1
                INCREMENT BY 1
                NO MINVALUE
                NO MAXVALUE
                CACHE 1
            );
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'rider_earnings_pkey') THEN
        ALTER TABLE ONLY public.rider_earnings
            ADD CONSTRAINT rider_earnings_pkey PRIMARY KEY (id);
    END IF;
END
$$;
