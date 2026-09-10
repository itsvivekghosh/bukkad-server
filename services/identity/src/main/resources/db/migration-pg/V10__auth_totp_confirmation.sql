-- W1-AUTH (feature #5): TOTP MFA confirmation stamp.
-- totp_enabled already exists (V1); customers/admins carry totp_secret.
-- This stamps WHEN the enrollment was confirmed with a live code.
ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS totp_confirmed_at timestamp(6) without time zone;
