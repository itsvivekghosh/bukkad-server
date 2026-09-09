-- PERF-2/D1: outbox relay backoff column for the platform reference schema.
-- Service databases carry the identical additive migration as their V8
-- (db/migration-pg/V8__outbox_next_attempt.sql in every service).
-- Mirrors OutboxEvent.nextAttemptAt (TIMESTAMP(6) per the plan §5.4 rule:
-- LocalDateTime maps to timestamp without time zone).

ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6);

-- Claim filter (status + due time) rides the existing idx_outbox_status_created;
-- eligible rows always have status=PENDING, so no extra index is warranted.
