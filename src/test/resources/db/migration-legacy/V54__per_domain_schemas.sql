-- =============================================================================
-- V54: Per-domain schemas (Phase 2 — vertical partitioning).
--
-- Creates one schema per owning domain so the same physical MySQL server can
-- host the partitioned monolith. The application still runs on the `bhukkad`
-- schema today; these schemas are the target for the Phase 3 service extraction
-- and let operators pre-provision privileges, backups and read replicas per
-- domain before any data moves.
--
-- Idempotent: CREATE DATABASE IF NOT EXISTS is safe to run on every deploy.
-- =============================================================================

CREATE DATABASE IF NOT EXISTS bhukkad_orders
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_payments
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_restaurants
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_customers
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_admin
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS bhukkad_delivery
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Grant the application user the same privileges it has on `bhukkad`.
-- GRANT ALL PRIVILEGES ON bhukkad_orders.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_payments.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_restaurants.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_customers.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_admin.* TO 'bhukkad_user'@'%';
-- GRANT ALL PRIVILEGES ON bhukkad_delivery.* TO 'bhukkad_user'@'%';
-- FLUSH PRIVILEGES;
