package com.bhukkad.outbox;

import com.bhukkad.event.OrderAgentAssignedEvent;
import com.bhukkad.event.OrderCreatedEvent;
import com.bhukkad.event.OrderStatusChangedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventProcessor {

    private static final int BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 5;

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:2000}")
    @Transactional
    public void processPendingEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findByStatus(
                OutboxEvent.OutboxStatus.PENDING,
                PageRequest.of(0, BATCH_SIZE));

        for (OutboxEvent event : pending) {
            try {
                publish(event);
                event.setStatus(OutboxEvent.OutboxStatus.PUBLISHED);
                event.setPublishedAt(LocalDateTime.now());
                event.setLastError(null);
            } catch (Exception ex) {
                event.setRetryCount(event.getRetryCount() + 1);
                event.setLastError(ex.getMessage());
                if (event.getRetryCount() >= MAX_RETRIES) {
                    event.setStatus(OutboxEvent.OutboxStatus.FAILED);
                    log.error("OUTBOX_FAILED | id={} | type={} | error={}",
                            event.getId(), event.getEventType(), ex.getMessage());
                } else {
                    log.warn("OUTBOX_RETRY | id={} | type={} | attempt={} | error={}",
                            event.getId(), event.getEventType(), event.getRetryCount(), ex.getMessage());
                }
            }
            outboxEventRepository.save(event);
        }
    }

    private void publish(OutboxEvent event) throws Exception {
        switch (event.getEventType()) {
            case "ORDER_CREATED" -> eventPublisher.publishEvent(
                    objectMapper.readValue(event.getPayload(), OrderCreatedEvent.class));
            case "ORDER_STATUS_CHANGED" -> eventPublisher.publishEvent(
                    objectMapper.readValue(event.getPayload(), OrderStatusChangedEvent.class));
            case "ORDER_AGENT_ASSIGNED" -> eventPublisher.publishEvent(
                    objectMapper.readValue(event.getPayload(), OrderAgentAssignedEvent.class));
            default -> throw new IllegalArgumentException("Unknown outbox event type: " + event.getEventType());
        }
    }
}
