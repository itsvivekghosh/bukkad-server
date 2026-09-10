-- W1-LOYALTY (feature #4 / ADR-005): referral binding integrity + reward ledger.
--
-- 1. A referred customer can never be re-bound: partial unique index on the
--    referee column (NULL rows — customers who have not used a code — stay
--    free to apply later).

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_referral_codes_referred_by
    ON public.user_referral_codes (referred_by)
    WHERE referred_by IS NOT NULL;

-- 2. Durable referral reward ledger (exactly-once reward accounting):
--    one row per credited reward, idempotent by (event_type, event_id) —
--    e.g. ("REFERRAL_APPLY", "apply:<customerId>") or
--    ("REFERRAL_COMPLETE", "order:<orderId>") — and at most one reward of a
--    given type per referred customer.

CREATE TABLE public.referral_rewards_ledger (
    id bigint NOT NULL,
    receiver_customer_id bigint NOT NULL,
    referred_customer_id bigint NOT NULL,
    reward_type character varying(30) NOT NULL,
    reward_amount double precision NOT NULL,
    event_type character varying(40) NOT NULL,
    event_id character varying(80) NOT NULL,
    order_id bigint,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT referral_rewards_ledger_pkey PRIMARY KEY (id),
    CONSTRAINT uq_referral_rewards_event UNIQUE (event_type, event_id),
    CONSTRAINT uq_referral_rewards_per_referee UNIQUE (referred_customer_id, reward_type)
);

CREATE SEQUENCE public.referral_rewards_ledger_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.referral_rewards_ledger_id_seq OWNED BY public.referral_rewards_ledger.id;
ALTER TABLE ONLY public.referral_rewards_ledger
    ALTER COLUMN id SET DEFAULT nextval('public.referral_rewards_ledger_id_seq'::regclass);

CREATE INDEX idx_referral_rewards_receiver
    ON public.referral_rewards_ledger USING btree (receiver_customer_id);

CREATE INDEX idx_referral_rewards_referee
    ON public.referral_rewards_ledger USING btree (referred_customer_id);
