package com.bhukkad.outbox;

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
 * to run it at scrape frequency on a hot fleet.
 */
@Slf4j
@Component
public class OutboxMetrics {

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
