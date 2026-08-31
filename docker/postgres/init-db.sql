-- Bhukkad PostgreSQL bootstrap (Docker only)
-- Schema + seed data are applied by Flyway when the application starts.

CREATE DATABASE IF NOT EXISTS bhukkad
    WITH ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.UTF-8'
    LC_CTYPE = 'en_US.UTF-8'
    TEMPLATE = template0;

GRANT ALL PRIVILEGES ON DATABASE bhukkad TO 'bhukkad';

SELECT 'Bhukkad database ready for Flyway migrations' AS status;
