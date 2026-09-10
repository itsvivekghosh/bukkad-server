-- =============================================================================
-- docs/sql/grants-select-only.sql
-- R-08 / G-14 database-layer enforcement: the supportticket DB role gets
-- READ-ONLY access to the foreign tables it needs as read models.
--
-- !! DOCUMENTED, NOT AUTO-APPLIED !!
--   * Flyway must NOT pick this up: it is a DBA runbook script, to be run by a
--     superuser against the cluster AFTER the per-service databases exist.
--   * It is intentionally idempotent (DO-block guards) so re-running after a
--     restore drill is safe.
--
-- Ownership model (docs/table-domain-matrix.csv, ADR-001):
--   * supportticket OWNS:  support schema (disputes, support_tickets) — full
--     CRUD via the support role.
--   * supportticket READS: order / identity (customer, user) / payment
--     (gift card) tables for display and dispute context. ADR-001: the write
--     path was deleted; these grants make a reintroduction fail at the DB layer
--     even if the ArchUnit rule is bypassed.
--   * Reads are legal, writes are not — the same read-yes/write-no line the
--     code layer enforces via SupportServiceArchTest
--     (noWritesViaCrossDomainRepositories bans save*/delete*/remove* calls and
--     @Modifying queries on any *Order*/*User*/*GiftCard*/*Wallet*/*Customer*/
--     *Payment* Repository; find*/get*/count*/exists* stay legal).
--
-- Cross-database access in PostgreSQL requires foreign data wrappers: the
-- foreign schemas are imported INTO the support database via postgres_fdw, and
-- the support role is granted SELECT (and nothing else) on them.
--
-- Verify after applying:
--   * positive: SELECT count(*) FROM orders.order_header;  -- works as support
--   * negative: INSERT INTO orders.order_header (...) VALUES (...);  -- must
--     fail with ERROR: permission denied for table order_header
--   * negative: UPDATE orders.order_header SET ...;       -- must fail
--   * negative: DELETE FROM orders.order_header;          -- must fail
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0. Run as cluster superuser. Adjust the role/user names to match the
--    deployment secrets (bhukkad-secrets: SUPPORT_DB_USERNAME etc.).
-- -----------------------------------------------------------------------------
\set support_role 'support_app'
\set admin_conn 'host=bhukkad-postgresql port=5432 dbname=orders'

-- 1. Roles are created out-of-band by provisioning (provision-vault.sh /
--    per-service Flyway bootstraps). Fail loudly rather than granting to a
--    typo'd role.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'support_role') THEN
        RAISE EXCEPTION 'R-08: role % does not exist — create it before granting', :'support_role';
    END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- 2. One-time postgres_fdw setup inside the support database.
--    Run:  psql -d support -f docs/sql/grants-select-only.sql
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

-- 2a. Foreign server pointing at the primary. The mapping user is a
--     READ-ONLY login on the foreign databases (see step 4) so a compromised
--     support role still cannot escalate to writes.
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_foreign_server WHERE srvname = 'bhukkad_cluster') THEN
        CREATE SERVER bhukkad_cluster
            FOREIGN DATA WRAPPER postgres_fdw
            OPTIONS (host 'bhukkad-postgresql', port '5432', dbname 'orders');
    END IF;
END
$$;

-- 2b. User mapping: support role -> foreign read-only user.
--     Replace 'support_reader' with the actual foreign read-only user.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT FROM pg_user_mappings
        WHERE srvname = 'bhukkad_cluster' AND usename = :'support_role') THEN
        CREATE USER MAPPING FOR :'support_role'
            SERVER bhukkad_cluster
            OPTIONS (user 'support_reader', password_required 'true');
    END IF;
END
$$;

-- -----------------------------------------------------------------------------
-- 3. Import the foreign schemas as FOREIGN SCHEMAS and grant SELECT ONLY.
--    `IMPORT FOREIGN SCHEMA ... LIMIT TO` is re-runnable: it recreates the
--    foreign table definitions if the source schema evolves.
-- -----------------------------------------------------------------------------
-- 3a. order domain (order service owns it)
IMPORT FOREIGN SCHEMA public
    FROM SERVER bhukkad_cluster INTO orders;

-- 3b. identity domain (customer/user read model)
--     Requires a second foreign server with dbname 'identity' — create it the
--     same way as step 2a (name: bhukkad_identity), then:
-- IMPORT FOREIGN SCHEMA public FROM SERVER bhukkad_identity INTO identities;

-- 3c. payment domain (gift card read model)
--     Same pattern with dbname 'payments' (name: bhukkad_payments):
-- IMPORT FOREIGN SCHEMA public FROM SERVER bhukkad_payments INTO payments;

GRANT USAGE ON SCHEMA orders TO :'support_role';
GRANT SELECT ON ALL TABLES IN SCHEMA orders TO :'support_role';
ALTER DEFAULT PRIVILEGES IN SCHEMA orders
    GRANT SELECT ON TABLES TO :'support_role';

-- Repeat for the identities / payments schemas once their servers exist:
-- GRANT USAGE ON SCHEMA identities TO :'support_role';
-- GRANT SELECT ON ALL TABLES IN SCHEMA identities TO :'support_role';
-- GRANT USAGE ON SCHEMA payments TO :'support_role';
-- GRANT SELECT ON ALL TABLES IN SCHEMA payments TO :'support_role';

-- -----------------------------------------------------------------------------
-- 4. The foreign-side login ('support_reader') must itself be read-only.
--    Run on EACH source database (orders, identity, payments) as superuser:
--
--    CREATE ROLE support_reader LOGIN PASSWORD '<from Vault bhukkad/prod/backup>';
--    GRANT CONNECT ON DATABASE <db> TO support_reader;
--    GRANT USAGE ON SCHEMA public TO support_reader;
--    GRANT SELECT ON ALL TABLES IN SCHEMA public TO support_reader;
--    ALTER DEFAULT PRIVILEGES IN SCHEMA public
--        GRANT SELECT ON TABLES TO support_reader;
--
--    Belt-and-braces: forbid even accidental writes by the support role on the
--    foreign side (revokes that were never granted are no-ops):
--    REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA public
--        FROM support_reader;
-- -----------------------------------------------------------------------------

-- -----------------------------------------------------------------------------
-- 5. Sanity assertions (run after applying; both must hold).
-- -----------------------------------------------------------------------------
-- 5a. The support role has NO write privileges anywhere except its own schema:
--     SELECT table_schema, privilege_type
--     FROM information_schema.role_table_grants
--     WHERE grantee = 'support_app'
--       AND table_schema NOT IN ('support')
--       AND privilege_type IN ('INSERT', 'UPDATE', 'DELETE', 'TRUNCATE');
--     => must return 0 rows.
-- 5b. Read grants exist:
--     SELECT count(*) FROM information_schema.role_table_grants
--     WHERE grantee = 'support_app' AND privilege_type = 'SELECT';
--     => must be > 0.
