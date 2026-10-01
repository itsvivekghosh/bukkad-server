-- Logical sharding for orders table (Phase 8).
-- Creates 16 shard schemas (shard_0 .. shard_15) and orders_v2 tables.
-- Run after the dual-write migration is validated.

CREATE SCHEMA IF NOT EXISTS shard_0;
CREATE SCHEMA IF NOT EXISTS shard_1;
CREATE SCHEMA IF NOT EXISTS shard_2;
CREATE SCHEMA IF NOT EXISTS shard_3;
CREATE SCHEMA IF NOT EXISTS shard_4;
CREATE SCHEMA IF NOT EXISTS shard_5;
CREATE SCHEMA IF NOT EXISTS shard_6;
CREATE SCHEMA IF NOT EXISTS shard_7;
CREATE SCHEMA IF NOT EXISTS shard_8;
CREATE SCHEMA IF NOT EXISTS shard_9;
CREATE SCHEMA IF NOT EXISTS shard_10;
CREATE SCHEMA IF NOT EXISTS shard_11;
CREATE SCHEMA IF NOT EXISTS shard_12;
CREATE SCHEMA IF NOT EXISTS shard_13;
CREATE SCHEMA IF NOT EXISTS shard_14;
CREATE SCHEMA IF NOT EXISTS shard_15;

-- orders_v2 in each shard (same schema as original orders table).
-- The id column retains the original sequence-backed value so existing
-- references remain valid during the dual-write window.
CREATE TABLE shard_0.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_1.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_2.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_3.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_4.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_5.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_6.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_7.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_8.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_9.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_10.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_11.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_12.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_13.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_14.orders_v2 (LIKE public.orders INCLUDING ALL);
CREATE TABLE shard_15.orders_v2 (LIKE public.orders INCLUDING ALL);

-- Indexes on customer_id for range scans within a shard.
CREATE INDEX idx_shard_0_orders_customer ON shard_0.orders_v2(customer_id);
CREATE INDEX idx_shard_1_orders_customer ON shard_1.orders_v2(customer_id);
CREATE INDEX idx_shard_2_orders_customer ON shard_2.orders_v2(customer_id);
CREATE INDEX idx_shard_3_orders_customer ON shard_3.orders_v2(customer_id);
CREATE INDEX idx_shard_4_orders_customer ON shard_4.orders_v2(customer_id);
CREATE INDEX idx_shard_5_orders_customer ON shard_5.orders_v2(customer_id);
CREATE INDEX idx_shard_6_orders_customer ON shard_6.orders_v2(customer_id);
CREATE INDEX idx_shard_7_orders_customer ON shard_7.orders_v2(customer_id);
CREATE INDEX idx_shard_8_orders_customer ON shard_8.orders_v2(customer_id);
CREATE INDEX idx_shard_9_orders_customer ON shard_9.orders_v2(customer_id);
CREATE INDEX idx_shard_10_orders_customer ON shard_10.orders_v2(customer_id);
CREATE INDEX idx_shard_11_orders_customer ON shard_11.orders_v2(customer_id);
CREATE INDEX idx_shard_12_orders_customer ON shard_12.orders_v2(customer_id);
CREATE INDEX idx_shard_13_orders_customer ON shard_13.orders_v2(customer_id);
CREATE INDEX idx_shard_14_orders_customer ON shard_14.orders_v2(customer_id);
CREATE INDEX idx_shard_15_orders_customer ON shard_15.orders_v2(customer_id);
