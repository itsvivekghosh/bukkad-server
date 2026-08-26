-- Outbox claim-and-process refactor: track when a sweep claimed an event so
-- the recovery sweep can reset events stranded in PROCESSING by a crashed
-- or killed pod back to PENDING.
ALTER TABLE outbox_events
    ADD COLUMN processing_started_at DATETIME(6) NULL AFTER published_at;
