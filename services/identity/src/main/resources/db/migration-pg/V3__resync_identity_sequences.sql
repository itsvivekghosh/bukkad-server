-- Repair identity sequence alignment after explicit-id inserts.
--
-- V2 backfilled users(id) rows copied 1:1 from customers.id; the first
-- admin row and any seeded customers/admins also arrive with explicit ids.
-- A GENERATED AS IDENTITY column does NOT advance its sequence on explicit
-- inserts, so the next register/login drew an already-used id and blew up
-- with `duplicate key value violates unique constraint "users_pkey"` (500).
--
-- setval(GREATEST(max(id), 1)) is idempotent and runs on fresh and existing
-- databases alike; it is safe to repeat.

SELECT setval(pg_get_serial_sequence('users', 'id'),
              (SELECT GREATEST(COALESCE(MAX(id), 1), 1) FROM users));

SELECT setval(pg_get_serial_sequence('customers', 'id'),
              (SELECT GREATEST(COALESCE(MAX(id), 1), 1) FROM customers));

SELECT setval(pg_get_serial_sequence('admins', 'id'),
              (SELECT GREATEST(COALESCE(MAX(id), 1), 1) FROM admins));
