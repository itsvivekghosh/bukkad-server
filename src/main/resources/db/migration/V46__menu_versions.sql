-- V46__menu_versions.sql
-- Adds snapshot-based menu versioning for restaurant owners.
--
--   menu_versions : one row per saved version of a restaurant's menu.
--     - snapshot_json holds a JSON snapshot of the live menu (categories + items)
--       captured when the version was created.
--     - status is DRAFT (not yet published) or PUBLISHED.
--     - publishing a version only flips status/published_at; the live menu that
--       the order flow reads is never modified.
--
-- The CREATE TABLE is guarded with IF NOT EXISTS so the migration is idempotent
-- on databases where the table may already exist from a manual hotfix.

CREATE TABLE IF NOT EXISTS menu_versions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    restaurant_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    label VARCHAR(100),
    snapshot_json MEDIUMTEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    UNIQUE KEY uq_menu_version (restaurant_id, version_number),
    KEY idx_menu_version_restaurant (restaurant_id)
);
