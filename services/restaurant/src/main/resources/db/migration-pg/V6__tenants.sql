-- ============================================================================
-- V6 — tenants table (Batch 4, migration-batch-4-restaurant)
-- ============================================================================
-- Adds the tenants table used by the new
-- com.bhukkad.restaurant.service.TenantService.
-- ============================================================================

CREATE TABLE tenants (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    domain      VARCHAR(200) NOT NULL,
    brand_name  VARCHAR(200),
    logo_url    VARCHAR(500),
    theme_color VARCHAR(20),
    currency    VARCHAR(3)   NOT NULL DEFAULT 'INR',
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMP(6) NOT NULL,
    updated_at  TIMESTAMP(6) NOT NULL
);

CREATE UNIQUE INDEX idx_tenant_domain ON tenants (lower(domain));