-- Bhukkad MySQL bootstrap (Docker only)
-- Schema + seed data are applied by Flyway when the application starts.

SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

CREATE DATABASE IF NOT EXISTS bhukkad
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE bhukkad;

GRANT ALL PRIVILEGES ON bhukkad.* TO 'bhukkad_user'@'%';
FLUSH PRIVILEGES;

SELECT 'Bhukkad database ready for Flyway migrations' AS status;
