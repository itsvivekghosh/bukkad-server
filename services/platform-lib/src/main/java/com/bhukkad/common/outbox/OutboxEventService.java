package com.bhukkad.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Marker-annotation-free outbox write service used by flows that build the
 * payload inline. Same G-1 contract as {@link OutboxClient}: enqueue must run
 * inside the caller's business transaction so the event commits atomically
 * with the domain change (or not at all when the transaction rolls back).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    public static final String AGGREGATE_ORDER = "ORDER";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, Long aggregateId, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "G-1 violation: outbox enqueue outside a business transaction — "
                            + "the event could outlive a rolled-back domain change");
        }
        try {
            OutboxEvent event = new OutboxEvent();
            event.setEventType(eventType);
            event.setAggregateType(AGGREGATE_ORDER);
            event.setAggregateId(aggregateId);
            event.setPayload(objectMapper.writeValueAsString(payload));
            event.setStatus(OutboxEvent.OutboxStatus.PENDING);
            outboxEventRepository.save(event);
            log.debug("OUTBOX_ENQUEUED | type={} | aggregateId={}", eventType, aggregateId);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to enqueue outbox event: " + eventType, e);
        }
    }
}
