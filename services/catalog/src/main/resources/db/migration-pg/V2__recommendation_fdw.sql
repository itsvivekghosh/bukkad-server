-- Recommendation-engine read access to order history.
--
-- RecommendationQueryServiceImpl scores customer affinity directly over the
-- order domain's tables (orders / order_items). Those live in the order
-- service's database; postgres_fdw foreign tables give the personalization
-- schema read access without a data copy (same pattern as the
-- admin-analytics V3 FDW migration).
--
-- The IMPORT is guarded: in single-DB environments the peer database may not
-- exist yet, in which case the foreign tables are simply absent and the
-- recommendation queries return empty results instead of failing startup.
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_foreign_server WHERE srvname = 'orders_srv') THEN
    CREATE SERVER orders_srv FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'orders');
  END IF;
END $$;

CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER orders_srv
    OPTIONS (user 'app', password 'app_pass');

DO $$
BEGIN
  BEGIN
    IMPORT FOREIGN SCHEMA public LIMIT TO (orders, order_items) FROM SERVER orders_srv INTO public;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'orders FDW import skipped: %', SQLERRM;
  END;
END $$;
