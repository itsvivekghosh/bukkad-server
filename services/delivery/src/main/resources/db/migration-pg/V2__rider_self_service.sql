-- Rider-app self-service surface (profile vehicle details + accept
-- timestamps). Additive-only per zero-breakage rules.
ALTER TABLE public.delivery_agents
    ADD COLUMN IF NOT EXISTS vehicle_type character varying(30),
    ADD COLUMN IF NOT EXISTS vehicle_number character varying(30);

ALTER TABLE public.delivery_assignments
    ADD COLUMN IF NOT EXISTS accepted_at timestamp(6) without time zone;
