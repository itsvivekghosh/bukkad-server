package com.bhukkad.common.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exposes outbox lag as Prometheus gauges so a stuck poller or a poisoned
 * consumer is visible before the dead-letter queue starts alerting.
 *
 * <p>The counts are refreshed on a fixed schedule (not per scrape) so Prometheus
 * scraping never fires a DB query — a {@code COUNT(*) WHERE status=...} on the
 * indexed status column is cheap even on a large outbox, but there is no reason
 * to run it at scrape frequency on a hot fleet.</p>
 *
 * <p>Metrics owned by the outbox pipeline (consistent {@code bhukkad.outbox.*}
 * naming; Prometheus renders dots as underscores):</p>
 * <ul>
 *   <li>{@code bhukkad.outbox.pending} — poller backlog gauge (here);</li>
 *   <li>{@code bhukkad.outbox.dead_letter} — DLQ depth gauge (here);</li>
 *   <li>{@code bhukkad.outbox.publish.lag} — publish-lag histogram in ms
 *       (P-06: now minus row createdAt at successful publish; recorded by
 *       {@link OutboxPollPublisher} at ack time, Prometheus
 *       {@code bhukkad_outbox_publish_lag_ms_*}). With the P-06 wake channel
 *       enabled this is the evidence for the E2E p99 &lt;2s gate.</li>
 *   <li>{@code outbox.dlq} — legacy dead-letter counter (PERF-2 alert metric,
 *       kept for dashboard continuity).</li>
 *   <li>{@code outbox.events.published} — events successfully published to
 *       Kafka (counter, recorded by {@link OutboxPollPublisher}); Prometheus
 *       {@code outbox_events_published}.</li>
 *   <li>{@code outbox.events.failed} — events that failed publish and were
 *       re-queued or dead-lettered (counter); Prometheus
 *       {@code outbox_events_failed}.</li>
 *   <li>{@code outbox.poll.duration} — poll cycle duration histogram (ms);
 *       Prometheus {@code outbox_poll_duration_ms_*}.</li>
 * </ul>
 */
@Slf4j
@Component
public class OutboxMetrics {

    /** Publish-lag histogram name (P-06); base unit ms, recorded at publish ack. */
    public static final String PUBLISH_LAG_METRIC_NAME = "bhukkad.outbox.publish.lag";
    /** Counter name for events successfully published to Kafka. */
    public static final String PUBLISHED_METRIC_NAME = "outbox.events.published";
    /** Counter name for events that failed to publish (re-queued or dead-lettered). */
    public static final String FAILED_METRIC_NAME = "outbox.events.failed";
    /** Timer name for the outbox poll cycle duration. */
    public static final String POLL_DURATION_METRIC_NAME = "outbox.poll.duration";

    private final OutboxEventRepository outboxEventRepository;
    private final DeadLetterEventService deadLetterEventService;

    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();
    private final Counter publishedCounter;
    private final Counter failedCounter;
    private final Timer pollDurationTimer;

    public OutboxMetrics(OutboxEventRepository outboxEventRepository,
                         DeadLetterEventService deadLetterEventService,
                         MeterRegistry meterRegistry) {
        this.outboxEventRepository = outboxEventRepository;
        this.deadLetterEventService = deadLetterEventService;
        Gauge.builder("bhukkad.outbox.pending", pendingCount, AtomicLong::get)
                .description("Outbox events waiting to be published (poller backlog)")
                .register(meterRegistry);
        Gauge.builder("bhukkad.outbox.dead_letter", deadLetterCount, AtomicLong::get)
                .description("Outbox events in the dead-letter queue awaiting requeue")
                .register(meterRegistry);
        this.publishedCounter = Counter.builder(PUBLISHED_METRIC_NAME)
                .description("Outbox events successfully published to Kafka")
                .register(meterRegistry);
        this.failedCounter = Counter.builder(FAILED_METRIC_NAME)
                .description("Outbox events that failed to publish and were re-queued or dead-lettered")
                .register(meterRegistry);
        this.pollDurationTimer = Timer.builder(POLL_DURATION_METRIC_NAME)
                .description("Outbox poll cycle duration")
                .register(meterRegistry);
    }

    /** Increments the published counter by {@code count}. */
    public void published(int count) {
        if (count > 0) {
            publishedCounter.increment(count);
        }
    }

    /** Increments the failed counter by {@code count}. */
    public void failed(int count) {
        if (count > 0) {
            failedCounter.increment(count);
        }
    }

    /** Records a poll cycle duration in milliseconds. */
    public void recordPollDuration(long durationMillis) {
        if (durationMillis >= 0) {
            pollDurationTimer.record(durationMillis, TimeUnit.MILLISECONDS);
        }
    }

    @Scheduled(fixedDelayString = "${app.outbox.metrics-refresh-ms:30000}")
    public void refresh() {
        try {
            pendingCount.set(outboxEventRepository.countByStatus(OutboxEvent.OutboxStatus.PENDING));
        } catch (Exception ex) {
            log.warn("OUTBOX_METRICS_FAILED | pending | error={}", ex.getMessage());
        }
        try {
            deadLetterCount.set(deadLetterEventService.countPending());
        } catch (Exception ex) {
            log.warn("OUTBOX_METRICS_FAILED | deadLetter | error={}", ex.getMessage());
        }
    }
}
