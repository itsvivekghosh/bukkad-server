package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.outbox.OutboxEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class KafkaPlatformEventPublisherTest {

    @Mock(lenient = true)
    private KafkaTemplate<String, String> kafkaTemplate;

    private ExternalEventsProperties properties;
    private KafkaPlatformEventPublisher publisher;

    @BeforeEach
    void setUp() {
        properties = new ExternalEventsProperties();
        properties.getKafka().setPlatformTopic("bhukkad.platform.events");
        publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, properties, new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @Test void publish_sendsJsonMessageToTopic() {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("ORDER_CREATED");
        event.setAggregateId(42L);
        event.setPayload("{\"orderId\":42}");

        publisher.publish(event);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                eq("bhukkad.platform.events"),
                eq("ORDER_CREATED"),
                payloadCaptor.capture());
        String sent = payloadCaptor.getValue();
        assertTrue(sent.contains("\"eventType\":\"ORDER_CREATED\""));
        assertTrue(sent.contains("\"aggregateId\":42"));
        assertTrue(sent.contains("\"payload\":\"{\\\"orderId\\\":42}\""));
    }

    @Test void publish_withNullPayload_doesNotThrow() {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("ORDER_STATUS_CHANGED");
        event.setAggregateId(7L);
        event.setPayload(null);

        publisher.publish(event);

        verify(kafkaTemplate).send(any(), any(), any());
    }
}
