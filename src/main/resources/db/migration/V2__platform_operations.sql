-- === V2__platform_operations.sql ===
-- Platform/Operations features:
--   * Multi-city/Region Support        -> city_configs
--   * Dark Kitchen Onboarding          -> restaurants.onboarding_status
--   * Fraud Dashboard review queue     -> fraud_review_queue
--   * Automated Dispute Resolution     -> disputes
--   * Promotion Engine (BOGO/segment)  -> promotion_campaigns extension columns
--   * Affiliate/Referral Program       -> affiliate_codes, affiliate_referrals
--   * White-label Solution             -> tenants, restaurants.tenant_id

-- ── Multi-city/Region Support ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS city_configs (
    id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
    city                       VARCHAR(100) NOT NULL,
    display_name               VARCHAR(120) NOT NULL,
    currency                   VARCHAR(10)  NOT NULL DEFAULT 'INR',
    timezone                   VARCHAR(60)  NOT NULL DEFAULT 'Asia/Kolkata',
    supported_payment_methods  VARCHAR(255),
    default_min_order_amount   DOUBLE       NOT NULL DEFAULT 0.0,
    is_serviceable             BOOLEAN      NOT NULL DEFAULT TRUE,
    is_active                  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP    NULL,
    UNIQUE KEY uk_city_configs_city (city)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Dark Kitchen Onboarding ─────────────────────────────────────────────────
ALTER TABLE restaurants
    ADD COLUMN onboarding_status VARCHAR(30) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN onboarding_rejection_reason VARCHAR(255) NULL;

-- ── Fraud Dashboard manual review queue ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS fraud_review_queue (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    fraud_event_id  BIGINT NOT NULL,
    customer_id     BIGINT NULL,
    action          VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    notes           VARCHAR(500) NULL,
    reviewed_by     BIGINT NULL,
    reviewed_at     TIMESTAMP NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_fraud_review_event FOREIGN KEY (fraud_event_id) REFERENCES fraud_events(id) ON DELETE CASCADE,
    CONSTRAINT fk_fraud_review_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE SET NULL,
    CONSTRAINT fk_fraud_review_user FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_fraud_review_event (fraud_event_id),
    INDEX idx_fraud_review_status (status),
    INDEX idx_fraud_review_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Automated Dispute Resolution ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS disputes (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id           BIGINT NOT NULL,
    type               VARCHAR(20) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    customer_evidence  TEXT,
    rider_evidence     TEXT,
    restaurant_evidence TEXT,
    resolution_notes   TEXT,
    resolution         VARCHAR(20) NULL,
    refund_amount      DOUBLE NULL,
    resolved_by        BIGINT NULL,
    resolved_at        TIMESTAMP NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_dispute_order FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_dispute_resolved_by FOREIGN KEY (resolved_by) REFERENCES users(id) ON DELETE SET NULL,
    UNIQUE KEY uk_dispute_order (order_id),
    INDEX idx_dispute_status (status),
    INDEX idx_dispute_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── Promotion Engine: buy X get Y + user segment ────────────────────────────
ALTER TABLE promotion_campaigns
    ADD COLUMN buy_quantity INT NULL,
    ADD COLUMN get_quantity INT NULL,
    ADD COLUMN get_discount_percent DOUBLE NULL,
    ADD COLUMN target_segment VARCHAR(30) NULL,
    ADD COLUMN applicable_menu_item_id BIGINT NULL;

-- ── Affiliate/Referral Program ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS affiliate_codes (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    code          VARCHAR(40) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    channel       VARCHAR(40) NULL,
    reward_amount DOUBLE NOT NULL DEFAULT 0.0,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_affiliate_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS affiliate_referrals (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    affiliate_code_id BIGINT NOT NULL,
    customer_id      BIGINT NOT NULL,
    reward_amount    DOUBLE NOT NULL DEFAULT 0.0,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_affiliate_referral_code FOREIGN KEY (affiliate_code_id) REFERENCES affiliate_codes(id) ON DELETE CASCADE,
    CONSTRAINT fk_affiliate_referral_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE,
    INDEX idx_affiliate_referral_code (affiliate_code_id),
    INDEX idx_affiliate_referral_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ── White-label Solution (tenant isolation) ─────────────────────────────────
CREATE TABLE IF NOT EXISTS tenants (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    domain      VARCHAR(120) NOT NULL,
    brand_name  VARCHAR(120) NULL,
    logo_url    VARCHAR(500) NULL,
    theme_color VARCHAR(30)  NULL,
    currency    VARCHAR(10)  NOT NULL DEFAULT 'INR',
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NULL,
    UNIQUE KEY uk_tenants_domain (domain)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE restaurants
    ADD COLUMN tenant_id BIGINT NULL,
    ADD CONSTRAINT fk_restaurant_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE SET NULL;
