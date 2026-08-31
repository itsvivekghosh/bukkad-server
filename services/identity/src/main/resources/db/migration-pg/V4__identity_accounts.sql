-- Identity service V4: account hierarchy + profile domain (Priority 1)
-- Port of the monolith's JOINED inheritance (users + role-specific subtables)
-- plus devices, consent, favorites, notification prefs, tenants, membership,
-- referral and affiliate state.

CREATE TABLE users (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    role              VARCHAR(20)  NOT NULL,
    active            BOOLEAN      NOT NULL DEFAULT true,
    email_verified    BOOLEAN      NOT NULL DEFAULT false,
    phone_verified    BOOLEAN      NOT NULL DEFAULT false,
    phone_verified_at TIMESTAMP(6),
    profile_completed BOOLEAN      NOT NULL DEFAULT false,
    totp_enabled      BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMP(6) DEFAULT now(),
    updated_at        TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_user_role ON users (role);
CREATE INDEX idx_user_active ON users (active);

CREATE TABLE user_referral_codes (
    user_id BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code    VARCHAR(20)  NOT NULL,
    CONSTRAINT uk_user_referral_code UNIQUE (code)
);

CREATE TABLE restaurant_owners (
    id              BIGINT       NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email           VARCHAR(100),
    full_name       VARCHAR(100),
    phone_number    VARCHAR(15),
    business_license VARCHAR(100),
    verified        BOOLEAN      NOT NULL DEFAULT false,
    created_at TIMESTAMP(6) DEFAULT now()
);
CREATE INDEX idx_owner_verified ON restaurant_owners (verified);

CREATE TABLE delivery_agents (
    id                BIGINT       NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email             VARCHAR(100),
    full_name         VARCHAR(100),
    phone_number      VARCHAR(15),
    available         BOOLEAN      NOT NULL DEFAULT false,
    verified          BOOLEAN      NOT NULL DEFAULT false,
    current_latitude  DOUBLE PRECISION,
    current_longitude DOUBLE PRECISION,
    average_rating    DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    total_deliveries  BIGINT       NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) DEFAULT now()
);
CREATE INDEX idx_agent_available_verified ON delivery_agents (available, verified);

CREATE TABLE admins (
    id            BIGINT      NOT NULL PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    email         VARCHAR(100),
    full_name     VARCHAR(100),
    phone_number  VARCHAR(15),
    created_at TIMESTAMP(6) DEFAULT now()
);
CREATE INDEX idx_admin_phone ON admins (phone_number);

CREATE TABLE device_tokens (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token       VARCHAR(512) NOT NULL,
    device_type VARCHAR(20),
    created_at TIMESTAMP(6) DEFAULT now()
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);

CREATE TABLE consent_records (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    purpose    VARCHAR(50)  NOT NULL,
    granted    BOOLEAN      NOT NULL,
    created_at TIMESTAMP(6) DEFAULT now(),
    CONSTRAINT uk_consent_user_purpose UNIQUE (user_id, purpose)
);

CREATE TABLE favorite_restaurants (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id   BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    restaurant_id BIGINT       NOT NULL,
    created_at TIMESTAMP(6) DEFAULT now(),
    CONSTRAINT uk_favorite_customer_restaurant UNIQUE (customer_id, restaurant_id)
);
CREATE INDEX idx_favorites_customer ON favorite_restaurants (customer_id);

CREATE TABLE customer_notification_preferences (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel         VARCHAR(20) NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) DEFAULT now(),
    CONSTRAINT uk_notif_pref_customer_channel UNIQUE (customer_id, channel)
);

CREATE TABLE tenants (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    domain     VARCHAR(120) NOT NULL,
    created_at TIMESTAMP(6) DEFAULT now(),
    CONSTRAINT uk_tenants_domain UNIQUE (domain)
);

CREATE TABLE membership_plans (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    tier        VARCHAR(20)  NOT NULL,
    price       NUMERIC(10,2) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) DEFAULT now()
);

CREATE TABLE customer_memberships (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id       BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    plan_id           BIGINT       NOT NULL REFERENCES membership_plans (id),
    status            VARCHAR(20)  NOT NULL,
    started_at        TIMESTAMP(6) NOT NULL,
    expires_at        TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) DEFAULT now()
);
CREATE INDEX idx_membership_customer ON customer_memberships (customer_id, status);

CREATE TABLE affiliate_codes (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    restaurant_id BIGINT      NOT NULL,
    code          VARCHAR(40) NOT NULL,
    commission_pct NUMERIC(5,2) NOT NULL DEFAULT 10.00,
    active        BOOLEAN     NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) DEFAULT now(),
    CONSTRAINT uk_affiliate_code UNIQUE (code)
);

CREATE TABLE affiliate_referrals (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    affiliate_code  VARCHAR(40) NOT NULL,
    referred_by     BIGINT,
    referred_user   BIGINT,
    status          VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) DEFAULT now()
);
CREATE INDEX idx_affiliate_referrals_code ON affiliate_referrals (affiliate_code);