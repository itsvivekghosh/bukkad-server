package com.bhukkad.notification.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit test for {@link NotificationEventConsumer}. Verifies that an incoming
 * {@link OrderCreated} event triggers the correct notification dispatch.
 */
@ExtendWith(MockitoExtension.class)
class NotificationEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private NotificationDispatchService dispatchService;

    @Captor
    private ArgumentCaptor<String> channelCaptor;
    @Captor
    private ArgumentCaptor<String> recipientCaptor;
    @Captor
    private ArgumentCaptor<String> templateCaptor;
    @Captor
    private ArgumentCaptor<String> subjectCaptor;
    @Captor
    private ArgumentCaptor<String> bodyCaptor;

    private NotificationEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new NotificationEventConsumer(dispatchService, objectMapper);
    }

    @Test
    void orderCreatedEvent_dispatchesNotification() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42",
                "{\"orderId\":42,\"customerId\":7,\"restaurantId\":10}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verify(dispatchService).dispatch(
                channelCaptor.capture(), recipientCaptor.capture(),
                templateCaptor.capture(), subjectCaptor.capture(), bodyCaptor.capture());

        assertThat(channelCaptor.getValue()).isEqualTo("email");
        assertThat(recipientCaptor.getValue()).isEqualTo("customer-7");
        assertThat(subjectCaptor.getValue()).contains("42");
    }

    @Test
    void nonOrderEvent_isIgnored() throws Exception {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42",
                "{\"orderId\":42,\"status\":\"DELIVERED\"}");

        consumer.onOrderEvent(objectMapper.writeValueAsString(event));

        verifyNoInteractions(dispatchService);
    }

    @Test
    void malformedPayload_doesNotThrow() {
        consumer.onOrderEvent("not-json");

        verifyNoInteractions(dispatchService);
    }
}