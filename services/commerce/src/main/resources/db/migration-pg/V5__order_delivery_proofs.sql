-- Delivery-proof handshake (OTP + photo) storage for the rider↔customer
-- handover flow. Additive-only.
CREATE TABLE IF NOT EXISTS public.order_delivery_proofs (
    id bigserial PRIMARY KEY,
    order_id bigint NOT NULL UNIQUE,
    otp_hash character varying(64),
    otp_issued_at timestamp(6) without time zone,
    otp_verified_at timestamp(6) without time zone,
    photo_url character varying(500),
    created_at timestamp(6) without time zone NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_order_delivery_proofs_order
    ON public.order_delivery_proofs (order_id);
