package com.bhukkad.common.outbox;

/**
 * P-06 outbox wake channel (publisher side): pings a per-service Redis
 * pub/sub channel after an outbox enqueue commits so the relay drains
 * immediately instead of waiting up to {@code pollInterval}.
 *
 * <p>Channel contract: {@code bhukkad:outbox:wake:<service>} where
 * {@code <service>} is {@code spring.application.name}. The message body is
 * the enqueued event type (diagnostics only — subscribers treat the channel
 * as a signal, never as data).</p>
 *
 * <p>Wake is an <strong>optimisation, never a correctness mechanism</strong>:
 * a lost/dropped wake is invisible because the periodic poll remains the
 * backstop. Publishers therefore swallow transport failures.</p>
 *
 * <p>Optional-bean pattern: {@link OutboxClient} holds a nullable instance;
 * when no bean exists (Redis absent or {@code app.outbox.wake.enabled=false},
 * the default) no wake is published and behaviour is identical to the
 * poll-only relay.</p>
 */
public interface OutboxWakePublisher {

    String WAKE_CHANNEL_PREFIX = "bhukkad:outbox:wake:";

    /**
     * Called from the enqueueing transaction's {@code afterCommit}
     * synchronization. Must never throw — a failed wake must not surface into
     * the business transaction.
     *
     * @param eventType the enqueued event type (diagnostic payload)
     */
    void publishAfterCommit(String eventType);

    /** Shared no-op instance semantics (used when wake is disabled/absent). */
    static OutboxWakePublisher noop() {
        return eventType -> { };
    }
}
