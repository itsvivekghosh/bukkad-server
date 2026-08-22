package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.notification.NotificationDispatchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformEventKafkaConsumerTest {

    @Mock
    private NotificationDispatchService notificationDispatchService;
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private PlatformEventKafkaConsumer consumer;
    private ExternalEventsProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ExternalEventsProperties();
        properties.getKafka().setPlatformTopic("bhukkad.platform.events");
        properties.getKafka().setDlqTopic("bhukkad.platform.events.dlt");
        consumer = new PlatformEventKafkaConsumer(
                notificationDispatchService,
                new ObjectMapper().registerModule(new JavaTimeModule()),
                properties, kafkaTemplate);
    }

    @Test
    void onPlatformEvent_validNotification_dispatches() {
        String payload = "{\"eventType\":\"ORDER_CREATED\",\"aggregateId\":5,"
                + "\"payload\":\"{}\",\"publishedAt\":\"2024-01-01T00:00:00Z\"}";

        consumer.onPlatformEvent(payload);

        verify(notificationDispatchService).dispatch(org.mockito.ArgumentMatchers.any(
                com.bhukkad.event.kafka.PlatformEventMessage.class));
    }

    @Test
    void onPlatformEvent_unprocessableMessage_routesToDlq() {
        // Malformed JSON: dispatch never happens, message goes to the DLQ topic.
        consumer.onPlatformEvent("{not-valid-json");

        verify(kafkaTemplate).send(eq("bhukkad.platform.events.dlt"), eq("DLQ"), anyString());
    }

    @Test
    void onPlatformEvent_dispatchFailure_routesToDlq() {
        String payload = "{\"eventType\":\"ORDER_CREATED\",\"aggregateId\":5,"
                + "\"payload\":\"{}\",\"publishedAt\":\"2024-01-01T00:00:00Z\"}";
        doThrow(new RuntimeException("notification service down"))
                .when(notificationDispatchService).dispatch(org.mockito.ArgumentMatchers.any(
                        com.bhukkad.event.kafka.PlatformEventMessage.class));

        consumer.onPlatformEvent(payload);

        verify(kafkaTemplate).send(eq("bhukkad.platform.events.dlt"), eq("DLQ"), anyString());
    }

    @Test
    void onPlatformEvent_nonNotificationEvent_ignored() {
        String payload = "{\"eventType\":\"SOME_OTHER_EVENT\",\"aggregateId\":5,"
                + "\"payload\":\"{}\",\"publishedAt\":\"2024-01-01T00:00:00Z\"}";

        consumer.onPlatformEvent(payload);

        // No dispatch, no DLQ routing for a valid but non-notification event.
        verify(notificationDispatchService, org.mockito.Mockito.never())
                .dispatch(org.mockito.ArgumentMatchers.any(
                        com.bhukkad.event.kafka.PlatformEventMessage.class));
        verify(kafkaTemplate, org.mockito.Mockito.never()).send(anyString(), anyString(), anyString());
    }
}
