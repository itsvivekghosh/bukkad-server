-- batch E (audit H4-1/H4-2 follow-up): refresh-token rotation metadata.
-- V4 introduced the store; this adds the rotation FAMILY (reuse detection
-- revokes every live member of a family at once) plus session metadata
-- (user_agent / device_id) for revocation tooling. Freshly added family ids
-- get their own family until the next rotation naturally replaces them.
ALTER TABLE public.refresh_tokens
    ADD COLUMN IF NOT EXISTS family_id character varying(36);
ALTER TABLE public.refresh_tokens
    ADD COLUMN IF NOT EXISTS user_agent character varying(512);
ALTER TABLE public.refresh_tokens
    ADD COLUMN IF NOT EXISTS device_id character varying(64);

UPDATE public.refresh_tokens
SET family_id = gen_random_uuid()::text
WHERE family_id IS NULL;

ALTER TABLE public.refresh_tokens
    ALTER COLUMN family_id SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family
    ON public.refresh_tokens USING btree (family_id);
