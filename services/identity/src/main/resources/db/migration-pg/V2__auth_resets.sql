-- Password-reset tokens (identity service). Tokens are stored hashed,
-- single-use, with a 30-minute expiry managed by the service.
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    user_id bigint NOT NULL PRIMARY KEY,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp(6) without time zone NOT NULL,
    used boolean DEFAULT false NOT NULL
);

-- Backfill: every existing customer without a users registry row gets one.
-- OVERRIDING SYSTEM VALUE must directly follow the column list (before SELECT).
INSERT INTO users (id, role, active, email_verified, phone_verified,
                   profile_completed, totp_enabled, created_at, updated_at)
OVERRIDING SYSTEM VALUE
SELECT c.id, 'CUSTOMER', COALESCE(c.is_active, true), COALESCE(c.email_verified, false),
       false, false, false, c.created_at, c.updated_at
FROM customers c
WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = c.id);
