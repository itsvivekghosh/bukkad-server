-- Add the rider proof-of-delivery columns that the delivery module's entity
-- referenced but no migration had ever created.
--
-- Context: after the 16->5 consolidation, order and delivery live in one
-- application. Both shipped an OrderDeliveryProof entity bound to the SAME
-- table (order_delivery_proofs) with DIFFERENT column sets:
--   order    -> otp_hash / otp_issued_at / otp_verified_at / photo_url
--   delivery -> photo_url / notes / signature / created_at
-- Two @Entity classes on one table is a hard Hibernate failure, and the
-- delivery copy would have thrown "column notes does not exist" on every proof
-- write even if it had started.
--
-- Fix: one merged entity (the union of both column sets) plus this additive
-- migration for the two columns the table was missing. Idempotent, so it is a
-- no-op on databases where they somehow already exist.
ALTER TABLE public.order_delivery_proofs
    ADD COLUMN IF NOT EXISTS notes character varying(500),
    ADD COLUMN IF NOT EXISTS signature character varying(500);
