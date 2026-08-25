package com.bhukkad.outbox;

import com.bhukkad.config.OutboxProperties;
import com.bhukkad.event.ExternalEventBridge;
import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderItemsSnapshotEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.bhukkad.event.PaymentWebhookReceivedEvent;
import com.bhukkad.logging.TracingBridge;
import com.bhukkad.logging.alert.AlertService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ExternalEventBridge externalEventBridge;
    private final DeadLetterEventService deadLetterEventService;
    private final OutboxProperties outboxProperties;
    private final AlertService alertService;

    /**
     * Sweeps the outbox. The claim query uses {@code FOR UPDATE SKIP LOCKED}
     * (see {@link OutboxEventRepository#findPendingForProcessing}) so exactly
     * one replica processes each event even though every replica runs this
     * poller; the ShedLock annotation below is defense-in-depth so the sweep
     * itself is single-runner when ShedLock is enabled.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @SchedulerLock(name = "outbox-processing", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    @Transactional
    public void processPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingForProcessing(
                OutboxEvent.OutboxStatus.PENDING.name(),
                outboxProperties.getBatchSize());

        for (OutboxEvent event : pending) {
            // When tracing is enabled, each outbox event gets its own child span so
            // Kafka publish + DB work is observable end-to-end (no-op otherwise).
            try (AutoCloseable span = TracingBridge.startSpan("outbox-" + event.getEventType())) {
                publish(event);
                externalEventBridge.forward(event);
                event.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                event.setLastError(null);
            } catch (Exception ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(ex.getMessage());
                if (event.getRetryCount() >= outboxProperties.getMaxRetries()) {
                    event.setStatus(OutboxEvent.OutboxStatus.FAILED);
                    deadLetterEventService.record(event, ex.getMessage());
                    long dlqSize = deadLetterEventService.countPending();
                    log.error("OUTBOX_FAILED | id={} | type={} | dlqSize={} | error={}",
                            event.getId(), event.getEventType(), dlqSize, ex.getMessage());
                    // Alert when the dead-letter queue grows so operators can
                    // investigate a stuck downstream consumer.
                    alertService.alertException("OutboxEventProcessor",
                            "Outbox event dead-lettered | id=" + event.getId()
                                    + " | type=" + event.getEventType()
                                    + " | dlqSize=" + dlqSize
                                    + " | error=" + ex.getMessage(), ex);
                } else {
                    log.warn("OUTBOX_RETRY | id={} | type={} | attempt={} | error={}",
                            event.getId(), event.getEventType(), event.getRetryCount(), ex.getMessage());
                }
            }
            outboxEventRepository.save(event);
        }
    }

    /**
     * Re-drives dead-lettered events back into the outbox. Runs on a
     * longer interval than the main sweep so transient downstream failures
     * (Kafka brokers down, etc.) get a chance to recover in between.
     */
    @Scheduled(fixedDelayString = "${app.outbox.dead-letter-repoll-ms:60000}")
    @Transactional
    public void requeueDeadLetters() {
        deadLetterEventService.requeuePending(outboxProperties.getDeadLetterBatchSize());
    }

    private void publish(OutboxEvent event) throws Exception {
        // publishEvent is invoked with an explicit (Object) cast: without it,
        // the compiler resolves to Spring 6.1's generic publishEvent(T) default
        // overload, which Mockito mocks differently from publishEvent(Object) —
        // causing "Wanted but not invoked" verify failures on the unit test.
        // The explicit cast is a no-op for a real ApplicationEventPublisher
        // (the generic default delegates to publishEvent(Object) anyway).
        switch (event.getEventType()) {
            case "ORDER_CREATED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderCreatedEvent.class));
            case "ORDER_STATUS_CHANGED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderStatusChangedEvent.class));
            case "ORDER_AGENT_ASSIGNED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderAgentAssignedEvent.class));
            case "ORDER_ITEMS_SNAPSHOT" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), OrderItemsSnapshotEvent.class));
            // Webhook receipts carry no side effects (the money path is applied
            // transactionally in the webhook controller). Route them to the
            // observability listener instead of dead-lettering every webhook.
            case "PAYMENT_WEBHOOK_RECEIVED" -> eventPublisher.publishEvent((Object) objectMapper
                    .readValue(event.getPayload(), PaymentWebhookReceivedEvent.class));
            default -> throw new IllegalArgumentException("Unknown outbox event type: " + event.getEventType());
        }
    }
}
