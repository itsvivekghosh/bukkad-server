package com.bhukkad.common.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * </ul>
 */
@Slf4j
@Component
public class OutboxMetrics {

    /** Publish-lag histogram name (P-06); base unit ms, recorded at publish ack. */
    public static final String PUBLISH_LAG_METRIC_NAME = "bhukkad.outbox.publish.lag";

    private final OutboxEventRepository outboxEventRepository;
    private final DeadLetterEventService deadLetterEventService;

    private final AtomicLong pendingCount = new AtomicLong();
    private final AtomicLong deadLetterCount = new AtomicLong();

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
