-- V20: Add missing virtual_brand_name column to restaurants table
--
-- The column was already added manually to unblock local development.
-- This version is intentionally a no-op so Flyway can advance the schema
-- history without attempting to recreate the existing column.

-- ALTER TABLE restaurants ADD COLUMN virtual_brand_name VARCHAR(100) NULL AFTER name;
