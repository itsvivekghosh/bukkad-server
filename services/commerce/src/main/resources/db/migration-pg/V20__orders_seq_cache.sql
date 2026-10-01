-- Sequence cache tuning for order ID generation (PERF-2).
-- CACHE 100 reduces round-trips to the sequence cache; for 1M TPS validation
-- increase to CACHE 1000.
ALTER SEQUENCE public.orders_id_seq CACHE 100;
