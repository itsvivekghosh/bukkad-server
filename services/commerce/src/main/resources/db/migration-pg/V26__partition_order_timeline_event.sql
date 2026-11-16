-- MN-06: Partition OrderTimelineEvent by month for query performance and
-- archival. This migration converts the append-only timeline_events table
-- into a partitioned table so old partitions can be dropped/archived without
-- expensive DELETE operations on a growing table.
--
-- Idempotent: safe to re-run after partial failure.
--
-- Strategy: Range partitioning by created_at month. Creates one partition
-- per month for the past 12 months plus 12 months forward, plus a DEFAULT
-- partition for anything outside the range.

DO $$
DECLARE
    t_exists BOOLEAN;
    t_old_exists BOOLEAN;
    partition_name TEXT;
    month_start DATE;
    month_end DATE;
    i INT;
BEGIN
    -- Check if the old (non-partitioned) table exists
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_name = 'order_timeline_event' 
          AND table_schema = 'public'
    ) INTO t_exists;

    -- Check if old renamed table exists (from previous partial migration)
    SELECT EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_name = 'order_timeline_event_old' 
          AND table_schema = 'public'
    ) INTO t_old_exists;

    -- Step 1: Rename existing table to _old if it exists and isn't already renamed
    IF t_exists AND NOT t_old_exists THEN
        ALTER TABLE order_timeline_event RENAME TO order_timeline_event_old;
    END IF;

    -- Step 2: Create partitioned table if it doesn't exist
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_name = 'order_timeline_event' 
          AND table_schema = 'public'
    ) THEN
        CREATE TABLE order_timeline_event (
            id BIGINT NOT NULL,
            order_id BIGINT NOT NULL,
            event_type VARCHAR(50) NOT NULL,
            payload JSONB,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            PRIMARY KEY (id, created_at)
        ) PARTITION BY RANGE (created_at);
    END IF;

    -- Step 3: Create monthly partitions
    FOR i IN -12..12 LOOP
        month_start := date_trunc('month', CURRENT_DATE) + (i || ' months')::interval;
        month_end := month_start + interval '1 month';
        partition_name := 'order_timeline_event_' || to_char(month_start, 'YYYY_MM');

        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF order_timeline_event FOR VALUES FROM (%L) TO (%L)',
            partition_name, month_start, month_end
        );
    END LOOP;

    -- Step 4: Default partition
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.tables 
        WHERE table_name = 'order_timeline_event_default' 
          AND table_schema = 'public'
    ) THEN
        CREATE TABLE order_timeline_event_default PARTITION OF order_timeline_event DEFAULT;
    END IF;

    -- Step 5: Migrate data from old table if it exists
    IF t_old_exists THEN
        INSERT INTO order_timeline_event (id, order_id, event_type, payload, created_at)
        SELECT id, order_id, event_type, payload, created_at
        FROM order_timeline_event_old
        ON CONFLICT (id, created_at) DO NOTHING;
    END IF;

    -- Step 6: Create indexes
    CREATE INDEX IF NOT EXISTS idx_order_timeline_event_order_id
        ON order_timeline_event (order_id);

    CREATE INDEX IF NOT EXISTS idx_order_timeline_event_event_type
        ON order_timeline_event (event_type);

    CREATE INDEX IF NOT EXISTS idx_order_timeline_event_created_at
        ON order_timeline_event (created_at DESC);

    -- Step 7: Drop old table if data was migrated
    IF t_old_exists THEN
        DROP TABLE IF EXISTS order_timeline_event_old;
    END IF;
END $$;
