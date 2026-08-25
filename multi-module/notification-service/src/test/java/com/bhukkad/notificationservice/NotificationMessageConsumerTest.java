package com.bhukkad.notificationservice;

import com.bhukkad.common.event.PlatformEvent;
import com.bhukkad.notificationservice.dispatch.NotificationDispatchService;
import com.bhukkad.notificationservice.event.NotificationMessageConsumer;
import com.bhukkad.notificationservice.event.NotificationRequestEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationMessageConsumerTest {

    @Mock
    private NotificationDispatchService dispatchService;

    private ObjectMapper objectMapper;
    private NotificationMessageConsumer consumer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        consumer = new NotificationMessageConsumer(dispatchService, objectMapper);
    }

    private PlatformEvent event(String eventId) throws Exception {
        NotificationRequestEvent request = new NotificationRequestEvent(
                eventId, 1L, 7L, "EMAIL", "a@b.c", "Subject", "Body", "order-service");
        return new PlatformEvent(
                NotificationRequestEvent.EVENT_TYPE,
                NotificationRequestEvent.AGGREGATE_TYPE,
                "notif-" + eventId,
                objectMapper.writeValueAsString(request),
                Instant.now());
    }

    @Test
    void dispatchesValidRequest() throws Exception {
        consumer.onMessage(event("abc"));

        verify(dispatchService, times(1)).dispatch(any());
    }

    @Test
    void ignoresNonNotificationEvents() {
        PlatformEvent other = new PlatformEvent("ORDER_CREATED", "ORDER", "42", "{}", Instant.now());

        consumer.onMessage(other);

        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void deduplicatesSameEventIdWithinWindow() throws Exception {
        consumer.onMessage(event("dup-1"));
        consumer.onMessage(event("dup-1"));

        verify(dispatchService, times(1)).dispatch(any());
    }

    @Test
    void toleratesMalformedPayload() {
        PlatformEvent malformed = new PlatformEvent(
                NotificationRequestEvent.EVENT_TYPE, "NOTIFICATION", "9", "not-json{", Instant.now());

        consumer.onMessage(malformed);

        verify(dispatchService, never()).dispatch(any());
    }

    @Test
    void skipsNullEvent() {
        consumer.onMessage(null);

        verify(dispatchService, never()).dispatch(any());
    }
}
