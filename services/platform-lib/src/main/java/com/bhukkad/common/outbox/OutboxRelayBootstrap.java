package com.bhukkad.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * Registers the outbox relay loops on the dedicated {@code relay-} scheduler
 * when the application is ready (PERF-2: replaces the shared default-scheduler
 * {@code @Scheduled} wrappers).
 *
 * <ul>
 *   <li>{@code poll} → fixed-delay {@code outbox.pollInterval};</li>
 *   <li>{@code recoverStale} → fixed-delay {@code outbox.processingTimeout},
 *       so PROCESSING rows stranded by a crashed replica are re-queued on a
 *       bounded horizon (they were previously only recoverable by manual
 *       invocation, i.e. never in production).</li>
 * </ul>
 *
 * <p>Because the whole {@link OutboxPlatformConfig} class is gated by the same
 * event-pipeline expression as the Kafka publisher, the gate itself ({@code
 * app.events.external.enabled} + {@code type}) decides whether these loops
 * ever exist — no additional runtime flag is consulted.</p>
 */
@Slf4j
public class OutboxRelayBootstrap {

    private final OutboxPollPublisher relay;
    private final OutboxProperties properties;
    private final ThreadPoolTaskScheduler scheduler;

    private volatile ScheduledFuture<?> pollHandle;
    private volatile ScheduledFuture<?> recoveryHandle;

    public OutboxRelayBootstrap(OutboxPollPublisher relay,
                                OutboxProperties properties,
                                ThreadPoolTaskScheduler scheduler) {
        this.relay = relay;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (pollHandle != null) {
            return; // ApplicationReadyEvent can fire more than once in test contexts
        }
        Duration pollInterval = properties.pollInterval();
        Duration recoveryInterval = properties.processingTimeout();
        // Fixed-delay (not cron): the next cycle waits for the previous one to
        // finish, so a slow broker never stacks overlapping drains on a replica.
        this.pollHandle = scheduler.scheduleWithFixedDelay(relay::poll, pollInterval);
        this.recoveryHandle = scheduler.scheduleWithFixedDelay(relay::recoverStale, recoveryInterval);
        log.info("OUTBOX_RELAY_STARTED | pollInterval={} | recoveryInterval={} | pool=relay-",
                pollInterval, recoveryInterval);
    }

    @EventListener(ContextClosedEvent.class)
    public synchronized void stop() {
        if (pollHandle != null) {
            pollHandle.cancel(false);
            pollHandle = null;
        }
        if (recoveryHandle != null) {
            recoveryHandle.cancel(false);
            recoveryHandle = null;
        }
    }
}
