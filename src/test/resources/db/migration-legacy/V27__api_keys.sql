-- === V27__api_keys.sql ===
-- Security: partner API key management for programmatic integrations.

-- ── API keys ─────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS api_keys (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    key_prefix VARCHAR(12) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    partner_id BIGINT,
    scopes VARCHAR(255),
    expires_at DATETIME(6),
    last_used_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_keys_prefix (key_prefix),
    INDEX idx_api_keys_status (status),
    INDEX idx_api_keys_partner (partner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
