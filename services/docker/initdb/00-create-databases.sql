-- One clean-named database per service (docs/ARCHITECTURE.md service catalog).
-- Created on first boot of the dev compose stack; each service's Flyway
-- V1__baseline.sql (platform tables + service tables) applies to its own database.
SELECT 'CREATE DATABASE identity'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'identity')\gexec
SELECT 'CREATE DATABASE restaurants'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'restaurants')\gexec
SELECT 'CREATE DATABASE orders'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'orders')\gexec
SELECT 'CREATE DATABASE payments'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'payments')\gexec
SELECT 'CREATE DATABASE delivery'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'delivery')\gexec
SELECT 'CREATE DATABASE notification'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notification')\gexec
SELECT 'CREATE DATABASE admin'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'admin')\gexec
SELECT 'CREATE DATABASE search'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'search')\gexec
SELECT 'CREATE DATABASE survey'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'survey')\gexec
SELECT 'CREATE DATABASE referral'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'referral')\gexec
SELECT 'CREATE DATABASE support'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'support')\gexec
SELECT 'CREATE DATABASE realtime'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'realtime')\gexec
SELECT 'CREATE DATABASE personalization'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'personalization')\gexec
SELECT 'CREATE DATABASE growth'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'growth')\gexec
