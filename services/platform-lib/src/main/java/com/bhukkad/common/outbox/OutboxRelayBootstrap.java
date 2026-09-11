package com.bhukkad.common.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
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
 *   <li>P-06 wake channel → when a wake {@link RedisMessageListenerContainer}
 *       is wired (opt-in via {@code app.outbox.wake.enabled=true}, default
 *       FALSE), subscribes to {@code bhukkad:outbox:wake:<service>} and
 *       triggers an immediate, coalesced relay drain on each wake (see
 *       {@link OutboxWakeDrainListener}), cutting publish latency from up to
 *       {@code pollInterval} down to milliseconds so the E2E p99 &lt;2s gate
 *       is reachable.</li>
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
    /** Null unless the P-06 wake channel is enabled and Redis is configured. */
    private final RedisMessageListenerContainer wakeContainer;
    private final String wakeChannel;

    private volatile ScheduledFuture<?> pollHandle;
    private volatile ScheduledFuture<?> recoveryHandle;

    public OutboxRelayBootstrap(OutboxPollPublisher relay,
                                OutboxProperties properties,
                                ThreadPoolTaskScheduler scheduler) {
        this(relay, properties, scheduler, null, null);
    }

    OutboxRelayBootstrap(OutboxPollPublisher relay,
                         OutboxProperties properties,
                         ThreadPoolTaskScheduler scheduler,
                         RedisMessageListenerContainer wakeContainer,
                         String wakeChannel) {
        this.relay = relay;
        this.properties = properties;
        this.scheduler = scheduler;
        this.wakeContainer = wakeContainer;
        this.wakeChannel = wakeChannel;
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
        if (wakeContainer != null) {
            wakeContainer.afterPropertiesSet();
            wakeContainer.start();
            log.info("OUTBOX_WAKE_SUBSCRIBED | channel={}", wakeChannel);
        }
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
        if (wakeContainer != null) {
            try {
                wakeContainer.stop();
                wakeContainer.destroy();
            } catch (Exception ex) {
                log.warn("OUTBOX_WAKE_SUBSCRIPTION_STOP_FAILED | error={}", ex.getMessage());
            }
        }
    }
}
