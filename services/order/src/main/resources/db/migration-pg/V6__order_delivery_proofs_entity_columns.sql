-- OrderDeliveryProof entity ↔ schema reconciliation.
--
-- The V1 baseline created order_delivery_proofs with the legacy monolith
-- shape (otp_code_hash / status / proof_type / photo_storage_key ...). The
-- strangler-era V3 migration used CREATE TABLE IF NOT EXISTS, so on any DB
-- where the baseline table already exists it was a no-op and the entity
-- columns (otp_hash / otp_verified_at / photo_url) never materialized —
-- every OTP issue/verify/proof read then failed with
-- "column otp_hash does not exist" (500 INTERNAL_ERROR).
--
-- Additive-only: adds the entity's columns to whatever shape exists. Fresh
-- DBs get the V3 shape plus these no-op guards; legacy-shape DBs gain the
-- entity columns. Old columns are left untouched for delivery-service parity.
ALTER TABLE public.order_delivery_proofs
    ADD COLUMN IF NOT EXISTS otp_hash character varying(128),
    ADD COLUMN IF NOT EXISTS otp_verified_at timestamp(6) without time zone,
    ADD COLUMN IF NOT EXISTS photo_url character varying(500);
