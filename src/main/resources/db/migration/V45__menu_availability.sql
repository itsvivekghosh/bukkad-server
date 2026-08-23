-- V45__menu_availability.sql
-- Adds dietary flags (is_veg / is_jain) and item-level availability hours
-- (available_from / available_until) to menu_items so menu listings, search
-- results and order validation can be filtered by diet profile and by time of
-- day.
--
-- Every statement is idempotent (information_schema guards + prepared
-- statements), mirroring V31__add_totp_mfa.sql, so the migration is safe on
-- databases where some of these columns may already exist from a manual
-- hotfix. is_veg already exists from V1 on fresh installs; the guard makes the
-- ALTER a no-op there.
--
-- NOTE: the originally requested composite index idx_menu_restaurant_veg
-- (restaurant_id, is_veg) is intentionally NOT created: menu_items has no
-- restaurant_id column (restaurant access is via category_id ->
-- menu_categories.restaurant_id), so that index would fail. The existing
-- idx_menu_item_is_veg index covers the veg filtering path instead.

SET @schema_name = DATABASE();

-- menu_items.is_veg
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'is_veg') = 0,
    'ALTER TABLE menu_items ADD COLUMN is_veg BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.is_jain
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'is_jain') = 0,
    'ALTER TABLE menu_items ADD COLUMN is_jain BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.available_from
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'available_from') = 0,
    'ALTER TABLE menu_items ADD COLUMN available_from TIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- menu_items.available_until
SET @sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'menu_items' AND COLUMN_NAME = 'available_until') = 0,
    'ALTER TABLE menu_items ADD COLUMN available_until TIME NULL',
    'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
