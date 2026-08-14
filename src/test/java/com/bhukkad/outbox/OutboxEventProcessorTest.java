package com.bhukkad.outbox;

import com.bhukkad.event.OrderCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ObjectMapper objectMapper;

    @InjectMocks
    private OutboxEventProcessor outboxEventProcessor;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxEventProcessor = new OutboxEventProcessor(outboxEventRepository, eventPublisher, objectMapper);
    }

    @Test
    void processPendingEvents_publishesAndMarksPublished() throws Exception {
        OrderCreatedEvent createdEvent = new OrderCreatedEvent(
                1L, "ORD-1", 2L, 3L, LocalDateTime.now());
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setId(10L);
        outboxEvent.setEventType("ORDER_CREATED");
        outboxEvent.setPayload(objectMapper.writeValueAsString(createdEvent));
        outboxEvent.setStatus(OutboxEvent.OutboxStatus.PENDING);
        outboxEvent.setRetryCount(0);

        when(outboxEventRepository.findByStatus(eq(OutboxEvent.OutboxStatus.PENDING), any(Pageable.class)))
                .thenReturn(List.of(outboxEvent));

        outboxEventProcessor.processPendingEvents();

        ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(1L, captor.getValue().orderId());
        assertEquals(OutboxEvent.OutboxStatus.PUBLISHED, outboxEvent.getStatus());
        verify(outboxEventRepository).save(outboxEvent);
    }
}
