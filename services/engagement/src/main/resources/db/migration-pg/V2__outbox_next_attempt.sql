-- PERF-2/D1 (audit B1): outbox relay retry backoff column.
-- Additive only. Mirrors com.bhukkad.common.outbox.OutboxEvent#nextAttemptAt;
-- TIMESTAMP(6) without time zone per the plan's LocalDateTime convention
-- (see V1 baseline header). The relay defers a failed row's re-claim to
-- now + retryBackoff * 2^attempts via this column, and the claim query filters
-- (next_attempt_at IS NULL OR next_attempt_at <= now).

ALTER TABLE public.outbox_events ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP(6);
