-- Phone-first registration: make email, full_name, and password nullable
-- so accounts can be created with only a phone number, then have details
-- appended later. MySQL unique indexes allow multiple NULLs, so the email
-- uniqueness on the existing idx_user_email index is preserved for real values.
ALTER TABLE users MODIFY COLUMN email VARCHAR(100) NULL;
ALTER TABLE users MODIFY COLUMN full_name VARCHAR(100) NULL;
ALTER TABLE users MODIFY COLUMN password VARCHAR(255) NULL;

-- Track phone verification status for the phone-first registration flow.
ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN phone_verified_at DATETIME(6) NULL;

-- Track whether a phone-first user has completed their profile
-- (added email, full name, password) to gate downstream features.
ALTER TABLE users ADD COLUMN profile_completed BOOLEAN NOT NULL DEFAULT FALSE;

-- Index for the phone-first login / lookup path (phone is already unique
-- via the entity mapping, but an explicit index on the verification
-- query plane is useful for admin and support lookups).
CREATE INDEX IF NOT EXISTS idx_user_phone_verified ON users (phone_verified);
