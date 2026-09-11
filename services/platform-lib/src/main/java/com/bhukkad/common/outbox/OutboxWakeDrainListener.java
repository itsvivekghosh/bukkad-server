package com.bhukkad.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * P-06 wake-channel drain trigger: converts a Redis wake message on
 * {@code bhukkad:outbox:wake:<service>} into an immediate relay drain,
 * coalesced so a burst of enqueues cannot thrash the relay.
 *
 * <p><strong>Coalescing contract:</strong> at most one extra drain per
 * {@code pollInterval / 4} window. Bursts inside the window collapse into a
 * single drain (last-drain timestamp check), and a wake arriving while a
 * drain is already queued/running is dropped (AtomicBoolean hand-off) — the
 * periodic poll plus the in-flight drain already cover those rows.</p>
 *
 * <p>The drain runs on the relay's dedicated scheduler (never on the Redis
 * listener thread), so a slow Kafka send cannot starve the pub/sub
 * connection.</p>
 */
@Slf4j
public class OutboxWakeDrainListener implements MessageListener {

    private final Runnable drain;
    private final Executor drainExecutor;
    private final long coalesceWindowMs;
    private final LongSupplier clock;

    /** Guards against pile-up: true while a wake drain is queued/running. */
    private final AtomicBoolean drainQueued = new AtomicBoolean();

    /** Start time of the last queued drain (epoch ms); MIN/2 = never drained (first wake always fires). */
    private volatile long lastDrainQueuedAt = Long.MIN_VALUE / 2;

    public OutboxWakeDrainListener(Runnable drain, Executor drainExecutor, Duration coalesceWindow) {
        this(drain, drainExecutor, coalesceWindow.toMillis(), System::currentTimeMillis);
    }

    OutboxWakeDrainListener(Runnable drain, Executor drainExecutor, long coalesceWindowMs, LongSupplier clock) {
        this.drain = drain;
        this.drainExecutor = drainExecutor;
        this.coalesceWindowMs = coalesceWindowMs;
        this.clock = clock;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        long now = clock.getAsLong();
        if (now - lastDrainQueuedAt < coalesceWindowMs) {
            log.debug("OUTBOX_WAKE_COALESCED | windowMs={}", coalesceWindowMs);
            return;
        }
        if (!drainQueued.compareAndSet(false, true)) {
            log.debug("OUTBOX_WAKE_DROPPED | drain already queued");
            return;
        }
        lastDrainQueuedAt = now;
        try {
            drainExecutor.execute(() -> {
                try {
                    drain.run();
                } finally {
                    drainQueued.set(false);
                }
            });
        } catch (RejectedExecutionException ex) {
            // Scheduler shut down / saturated: reset so a later wake can fire.
            drainQueued.set(false);
            log.warn("OUTBOX_WAKE_DRAIN_REJECTED | error={}", ex.getMessage());
        }
    }
}
