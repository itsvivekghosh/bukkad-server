-- Analytics CSV exports read order/restaurant/payment/rider data that lives
-- in the other services' databases. postgres_fdw foreign tables give the
-- admin schema read access without a cross-service data copy. Read-only:
-- the export service only SELECTs from these.
--
-- The IMPORTs are individually guarded: in single-DB environments (unit-test
-- containers, fresh dev boxes) the peer databases may not exist yet, in which
-- case the foreign tables are simply absent and the exports return empty
-- CSVs instead of failing startup.
CREATE EXTENSION IF NOT EXISTS postgres_fdw;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_foreign_server WHERE srvname = 'orders_srv') THEN
    CREATE SERVER orders_srv FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'orders');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_foreign_server WHERE srvname = 'restaurants_srv') THEN
    CREATE SERVER restaurants_srv FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'restaurants');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_foreign_server WHERE srvname = 'payments_srv') THEN
    CREATE SERVER payments_srv FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'payments');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_foreign_server WHERE srvname = 'delivery_srv') THEN
    CREATE SERVER delivery_srv FOREIGN DATA WRAPPER postgres_fdw OPTIONS (dbname 'delivery');
  END IF;
END $$;

CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER orders_srv
    OPTIONS (user 'app', password 'app_pass');
CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER restaurants_srv
    OPTIONS (user 'app', password 'app_pass');
CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER payments_srv
    OPTIONS (user 'app', password 'app_pass');
CREATE USER MAPPING IF NOT EXISTS FOR CURRENT_USER SERVER delivery_srv
    OPTIONS (user 'app', password 'app_pass');

DO $$
BEGIN
  BEGIN
    IMPORT FOREIGN SCHEMA public LIMIT TO (orders) FROM SERVER orders_srv INTO public;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'orders FDW import skipped: %', SQLERRM;
  END;
  BEGIN
    IMPORT FOREIGN SCHEMA public LIMIT TO (restaurants) FROM SERVER restaurants_srv INTO public;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'restaurants FDW import skipped: %', SQLERRM;
  END;
  BEGIN
    IMPORT FOREIGN SCHEMA public LIMIT TO (payments) FROM SERVER payments_srv INTO public;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'payments FDW import skipped: %', SQLERRM;
  END;
  BEGIN
    IMPORT FOREIGN SCHEMA public LIMIT TO (delivery_agents) FROM SERVER delivery_srv INTO public;
  EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'delivery_agents FDW import skipped: %', SQLERRM;
  END;
END $$;
