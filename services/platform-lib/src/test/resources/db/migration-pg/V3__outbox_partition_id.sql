-- Partition sharding for parallel outbox relay (PERF-2).
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS partition_id INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_outbox_partition ON outbox_events(partition_id, status, created_at);
