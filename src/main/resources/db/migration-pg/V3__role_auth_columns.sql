-- V62 semantics for PostgreSQL: per-role account tables carry their own
-- auth/profile columns (email, password, full_name, phone_number,
-- profile_image_url, totp_secret) after the users table was trimmed to an
-- identity registry. This is the PG port of V62__user_role_segregation.sql.

-- customers — auth columns + referral
ALTER TABLE customers
    ADD COLUMN email VARCHAR(100),
    ADD COLUMN password VARCHAR(255),
    ADD COLUMN full_name VARCHAR(100),
    ADD COLUMN phone_number VARCHAR(15),
    ADD COLUMN profile_image_url VARCHAR(500),
    ADD COLUMN totp_secret VARCHAR(255),
    ADD COLUMN referrer_id BIGINT;
CREATE UNIQUE INDEX idx_customer_email ON customers (email);
CREATE INDEX idx_customer_phone ON customers (phone_number);

-- restaurant_owners — auth columns
ALTER TABLE restaurant_owners
    ADD COLUMN email VARCHAR(100),
    ADD COLUMN password VARCHAR(255),
    ADD COLUMN full_name VARCHAR(100),
    ADD COLUMN phone_number VARCHAR(15),
    ADD COLUMN profile_image_url VARCHAR(500),
    ADD COLUMN totp_secret VARCHAR(255);
CREATE UNIQUE INDEX idx_owner_email ON restaurant_owners (email);
CREATE INDEX idx_owner_phone ON restaurant_owners (phone_number);

-- delivery_agents — auth columns
ALTER TABLE delivery_agents
    ADD COLUMN email VARCHAR(100),
    ADD COLUMN password VARCHAR(255),
    ADD COLUMN full_name VARCHAR(100),
    ADD COLUMN phone_number VARCHAR(15),
    ADD COLUMN profile_image_url VARCHAR(500),
    ADD COLUMN totp_secret VARCHAR(255);
CREATE UNIQUE INDEX idx_agent_email ON delivery_agents (email);
CREATE INDEX idx_agent_phone ON delivery_agents (phone_number);

-- admins already carries its auth columns in V2 (email/password/full_name/
-- phone_number/profile_image_url/totp_secret).
