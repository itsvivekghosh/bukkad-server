-- PostgreSQL configuration for heavy traffic (200k+ TPS)
-- This script runs during container initialization to set server-wide parameters
-- that cannot be set per-database.

-- max_connections: 5000 connections
-- Each service uses PgBouncer (transaction pooling) with 500 pool size
-- 6 services * 500 = 3000 connections + headroom for admin/monitoring
ALTER SYSTEM SET max_connections = 5000;

-- Shared buffers: 25% of available RAM (for 8GB container = 2GB)
-- This is the main cache for PostgreSQL data pages
ALTER SYSTEM SET shared_buffers = '2GB';

-- effective_cache_size: 75% of available RAM
-- Tells planner how much memory is available for disk caching
ALTER SYSTEM SET effective_cache_size = '6GB';

-- Work memory: 64MB per operation (sort, hash, etc.)
-- For 5000 connections, be conservative: 5000 * 64MB = 320GB max (but not all active)
-- Typical concurrent operations ~100-500
ALTER SYSTEM SET work_mem = '64MB';

-- Maintenance work memory: for VACUUM, CREATE INDEX, etc.
ALTER SYSTEM SET maintenance_work_mem = '1GB';

-- WAL settings for durability + performance
ALTER SYSTEM SET wal_buffers = '64MB';
ALTER SYSTEM SET min_wal_size = '2GB';
ALTER SYSTEM SET max_wal_size = '8GB';
ALTER SYSTEM SET wal_compression = on;
ALTER SYSTEM SET wal_log_hints = on;

-- Checkpoint settings - spread out checkpoints to reduce I/O spikes
ALTER SYSTEM SET checkpoint_completion_target = 0.9;
ALTER SYSTEM SET checkpoint_timeout = '15min';

-- Parallel query settings
ALTER SYSTEM SET max_parallel_workers_per_gather = 4;
ALTER SYSTEM SET max_parallel_workers = 8;
ALTER SYSTEM SET parallel_leader_participation = on;

-- Statistics collector
ALTER SYSTEM SET track_counts = on;
ALTER SYSTEM SET track_io_timing = on;
ALTER SYSTEM SET track_functions = pl;

-- Logging
ALTER SYSTEM SET log_min_duration_statement = 1000;  -- log queries > 1s
ALTER SYSTEM SET log_checkpoints = on;
ALTER SYSTEM SET log_connections = on;
ALTER SYSTEM SET log_disconnections = on;
ALTER SYSTEM SET log_lock_waits = on;

-- Autovacuum tuning for high traffic
ALTER SYSTEM SET autovacuum_max_workers = 6;
ALTER SYSTEM SET autovacuum_naptime = '10s';
ALTER SYSTEM SET autovacuum_vacuum_threshold = 50;
ALTER SYSTEM SET autovacuum_vacuum_scale_factor = 0.05;
ALTER SYSTEM SET autovacuum_analyze_threshold = 50;
ALTER SYSTEM SET autovacuum_analyze_scale_factor = 0.05;
ALTER SYSTEM SET autovacuum_vacuum_cost_delay = '2ms';
ALTER SYSTEM SET autovacuum_vacuum_cost_limit = 2000;

-- Connection pooling / PgBouncer hints
ALTER SYSTEM SET max_prepared_transactions = 100;

-- JIT compilation (PostgreSQL 14+)
ALTER SYSTEM SET jit = on;
ALTER SYSTEM SET jit_above_cost = 100000;

-- Reload configuration (will be applied on next startup or pg_reload_conf)
SELECT pg_reload_conf();