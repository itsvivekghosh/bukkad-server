-- One database per service (plan §5.1 ownership matrix). Created on first boot
-- of the dev compose stack so each service's Flyway baseline (V1 common +
-- V2+ service) applies to its own database.
SELECT 'CREATE DATABASE bhukkad_identity'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_identity')\gexec
SELECT 'CREATE DATABASE bhukkad_restaurants'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_restaurants')\gexec
SELECT 'CREATE DATABASE bhukkad_orders'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_orders')\gexec
SELECT 'CREATE DATABASE bhukkad_payments'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_payments')\gexec
SELECT 'CREATE DATABASE bhukkad_delivery'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_delivery')\gexec
SELECT 'CREATE DATABASE bhukkad_notification'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_notification')\gexec
SELECT 'CREATE DATABASE bhukkad_admin'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'bhukkad_admin')\gexec
