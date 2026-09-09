-- Refresh-token store (identity service, audit follow-up): bearer access
-- tokens are short-lived (15 min); the refresh token is a 256-bit random
-- value persisted ONLY as a SHA-256 hex hash, rotated on every use.
-- Reuse of a revoked/hash-missing token is rejected with 401.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

ALTER TABLE public.refresh_tokens ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.refresh_tokens_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

-- Session lookup on login/logout (revoke-all-per-customer) and the
-- housekeeping purge (delete where expires_at < now).
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_customer ON refresh_tokens (customer_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens (expires_at);
