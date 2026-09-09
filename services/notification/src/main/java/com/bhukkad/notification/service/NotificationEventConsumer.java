package com.bhukkad.notification.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.RejectedExecutionException;

/**
 * Consumes platform events (P6 wiring) and dispatches outbound notifications.
 *
 * <p>Listens on the {@code order.events.v1} topic produced by the order
 * service. On {@code OrderCreated} a confirmation notification is dispatched
 * to the customer. The consumer is registered only when Kafka is enabled
 * ({@code app.events.external.enabled=true}) via platform-lib's
 * {@code KafkaPlatformConfig} — otherwise this bean is inert (never created),
 * so the service still boots with {@code type: log}.</p>
 *
 * <p><strong>PERF-2 hardening:</strong></p>
 * <ul>
 *   <li><em>V-10</em> — no more blanket {@code catch (Exception){log}}: parse
 *       errors and extraction failures surface to the container's
 *       {@code DefaultErrorHandler}, which retries and parks the poison record
 *       on {@code order.events.v1.dlt}.</li>
 *   <li><em>V-22</em> — a payload without a real {@code customerId} is a
 *       {@link PoisonEventException}, never a phantom {@code customer-0}
 *       send.</li>
 *   <li><em>P-07</em> — SMTP/Twilio sends (0.1–2 s) are handed to the bounded
 *       {@code notify-} pool, keeping the listener thread free. The
 *       {@code KAFKA_CONSUME} eventId claim commits BEFORE hand-off (dedupe
 *       wins the race for replays); executor rejection releases the claim and
 *       rethrows so the record reaches the DLT for later replay.</li>
 *   <li><em>Metrics</em> — {@code notification_dispatch_duration{channel}} and
 *       {@code notification_dispatch_queue_depth} (gauge registered in
 *       {@link com.bhukkad.notification.config.NotificationDispatchConfig}).</li>
 * </ul>
 */
@Slf4j
@Component
public class NotificationEventConsumer {

    static final String TOPIC_ORDER_EVENTS = "order.events.v1";
    static final String TYPE_ORDER_CREATED = "OrderCreated";
    private static final Duration CONSUME_DEDUPE_TTL = Duration.ofHours(48);

    private final NotificationDispatchService dispatchService;
    private final ObjectMapper objectMapper;
    private final IdempotencyRecordRepository idempotencyRecords;
    private final ThreadPoolTaskExecutor dispatchExecutor;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;

    public NotificationEventConsumer(NotificationDispatchService dispatchService,
                                     ObjectMapper objectMapper,
                                     IdempotencyRecordRepository idempotencyRecords,
                                     @Qualifier(com.bhukkad.notification.config.NotificationDispatchConfig.DISPATCH_EXECUTOR)
                                     ThreadPoolTaskExecutor dispatchExecutor,
                                     TransactionTemplate transactionTemplate,
                                     MeterRegistry meterRegistry) {
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
        this.idempotencyRecords = idempotencyRecords;
        this.dispatchExecutor = dispatchExecutor;
        this.transactionTemplate = transactionTemplate;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(topics = TOPIC_ORDER_EVENTS,
            groupId = "${app.events.external.kafka.consumer-group}")
    public void onOrderEvent(String payload) {
        PlatformEventMessage event = parseEnvelope(payload);
        if (!TYPE_ORDER_CREATED.equals(event.eventType())) {
            return; // deliberate skip: not our event type
        }
        long customerId = extractCustomerId(event.payload(), event.eventId());

        // Dispatch-keyed dedupe row committed BEFORE hand-off (PERF-2/P-07):
        // only the first delivery of an eventId may queue work.
        int claimed = claimEventId(event.eventId(), customerId);
        if (claimed == 0) {
            log.debug("NOTIFICATION_DUPLICATE_SKIPPED | eventId={}", event.eventId());
            return;
        }

        String orderId = event.aggregateId();
        try {
            dispatchExecutor.execute(() ->
                    dispatchWithMetrics(orderId, customerId, event.eventId()));
        } catch (RejectedExecutionException rejected) {
            // Queue full (AbortPolicy): undo the claim so a DLT replay can
            // re-dispatch, then rethrow -> DefaultErrorHandler -> DLT. Never
            // CallerRuns: the listener thread must not inline provider HTTP.
            releaseClaim(event.eventId());
            log.warn("NOTIFICATION_DISPATCH_REJECTED | eventId={} | queue full -> DLT", event.eventId());
            throw rejected;
        }
    }

    private void dispatchWithMetrics(String orderId, long customerId, String eventId) {
        long startedAt = System.nanoTime();
        try {
            dispatchService.dispatch(
                    "email", "customer-" + customerId,
                    "order-confirmation",
                    "Order " + orderId + " confirmed",
                    "Your order " + orderId + " is confirmed. Thank you!");
            log.info("NOTIFICATION_DISPATCHED | orderId={} | customerId={} | eventId={}",
                    orderId, customerId, eventId);
        } catch (Exception e) {
            // Runs on the dispatch pool AFTER the Kafka record was handled;
            // provider-side resilience (circuit breaker/bulkhead senders) and
            // the notifications table carry the retry story here.
            log.error("NOTIFICATION_DISPATCH_FAILED | orderId={} | customerId={} | error={}",
                    orderId, customerId, e.getMessage(), e);
        } finally {
            Timer.builder("notification_dispatch_duration")
                    .description("Outbound notification dispatch latency (P-07)")
                    .tag("channel", "email")
                    .register(meterRegistry)
                    .record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private PlatformEventMessage parseEnvelope(String payload) {
        try {
            return objectMapper.readValue(payload, PlatformEventMessage.class);
        } catch (Exception e) {
            throw new PoisonEventException("Malformed event envelope", e);
        }
    }

    /** V-22: a missing/zero customerId is POISON — route to DLT, never dispatch to customer-0. */
    private long extractCustomerId(String payload, String eventId) {
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        } catch (Exception e) {
            throw new PoisonEventException("Unparsable OrderCreated payload: eventId=" + eventId, e);
        }
        long customerId = node.path("customerId").asLong(0L);
        if (customerId <= 0) {
            throw new PoisonEventException("customerId missing or non-positive: eventId=" + eventId);
        }
        return customerId;
    }

    private int claimEventId(String eventId, long customerId) {
        if (eventId == null || eventId.isBlank()) {
            throw new PoisonEventException("OrderCreated without eventId");
        }
        Integer claimed = transactionTemplate.execute(status -> idempotencyRecords.insertIfAbsent(
                eventId,
                IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME.name(),
                customerId,
                IdempotencyRecord.IdempotencyStatus.COMPLETED.name(),
                null,
                LocalDateTime.now().plus(CONSUME_DEDUPE_TTL)));
        return claimed == null ? 0 : claimed;
    }

    private void releaseClaim(String eventId) {
        try {
            transactionTemplate.executeWithoutResult(status -> idempotencyRecords
                    .deleteByScopeAndIdempotencyKey(
                            IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME, eventId));
        } catch (Exception e) {
            log.warn("NOTIFICATION_CLAIM_RELEASE_FAILED | eventId={} | error={}",
                    eventId, e.getMessage());
        }
    }
}
