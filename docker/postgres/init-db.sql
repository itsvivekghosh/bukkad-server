-- Application PostgreSQL bootstrap (Docker dev only).
-- Fresh-start convention: one superuser role `app` owns every per-service
-- database. Schema + seed data are applied by Flyway when each service starts.

-- Dev convenience database (matches POSTGRES_DB in docker-compose.dev.yml).
SELECT 'CREATE DATABASE core'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'core')\gexec

-- Per-service databases (must match the *_DB_URL defaults in each
-- services/*/src/main/resources/application.yml).
SELECT 'CREATE DATABASE ' || dbname
FROM (VALUES ('orders'), ('restaurants'), ('identity'), ('payments'),
             ('delivery'), ('notification'), ('search'), ('support'),
             ('survey'), ('referral'), ('admin'), ('growth'),
             ('personalization'), ('realtime')) AS service_dbs(dbname)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = dbname)\gexec

GRANT ALL PRIVILEGES ON DATABASE core, orders, restaurants, identity,
  payments, delivery, notification, search, support, survey, referral,
  admin, growth, personalization, realtime TO "app";

SELECT 'Application databases ready for Flyway migrations' AS status;
