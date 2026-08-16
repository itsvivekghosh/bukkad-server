package com.bhukkad.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxEventService {

    public static final String AGGREGATE_ORDER = "ORDER";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void enqueue(String eventType, Long aggregateId, Object payload) {
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
