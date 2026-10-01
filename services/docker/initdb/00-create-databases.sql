-- One database per CONSOLIDATED service (post 16→5 merge).
--
-- Each service runs its own Flyway migrations from
-- services/<svc>/src/main/resources/db/migration-pg. The migration files from
-- the pre-merge services were copied verbatim into the merged service, so the
-- table/sequence names inside each database are UNCHANGED — only the database
-- names below are new. That keeps every baseline V1__baseline.sql and follow-on
-- migration valid without renames.
--
--   identity    -> identity      (unchanged)
--   commerce    -> commerce      (orders + payments + delivery migrations merged in)
--   catalog     -> catalog       (restaurants + search + personalization merged in)
--   engagement  -> engagement    (social + growth + referral + survey +
--                                 notification + realtime merged in)
--   admin       -> admin         (admin-analytics + support merged in)
--
-- Runs only on first boot of an empty data directory; every statement is
-- idempotent so re-running it against an existing volume is a no-op.
SELECT 'CREATE DATABASE identity'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'identity')\gexec
SELECT 'CREATE DATABASE commerce'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'commerce')\gexec
SELECT 'CREATE DATABASE catalog'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'catalog')\gexec
SELECT 'CREATE DATABASE engagement'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'engagement')\gexec
SELECT 'CREATE DATABASE admin'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'admin')\gexec
