package com.bhukkad.event.kafka;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.outbox.OutboxEvent;
import com.bhukkad.logging.LoggingConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private OutboxEvent event() {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("ORDER_CREATED");
        event.setAggregateId(42L);
        event.setPayload("{\"orderId\":42}");
        return event;
    }

    @Test
    void publish_sendsProducerRecordWithPayload() {
        publisher.publish(event());

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();
        assertEquals("bhukkad.platform.events", record.topic());
        assertEquals("ORDER_CREATED", record.key());
        assertTrue(record.value().contains("\"eventType\":\"ORDER_CREATED\""));
        assertTrue(record.value().contains("\"aggregateId\":42"));
    }

    @Test
    void publish_propagatesTraceparentHeaderFromMdc() {
        MDC.put(LoggingConstants.TRACE_ID, "abcdef0123456789abcdef0123456789");
        try {
            publisher.publish(event());
        } finally {
            MDC.clear();
        }

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        byte[] traceparent = captor.getValue().headers().lastHeader("traceparent").value();
        String value = new String(traceparent, StandardCharsets.UTF_8);
        assertTrue(value.startsWith("00-abcdef0123456789abcdef0123456789-"));
        assertTrue(value.endsWith("-01"));
    }

    @Test
    void publish_noTraceId_omitsTraceparentHeader() {
        MDC.clear();
        publisher.publish(event());

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertNull(captor.getValue().headers().lastHeader("traceparent"));
        assertNotNull(captor.getValue().key());
    }

    @Test
    void publish_withNullPayload_doesNotThrow() {
        OutboxEvent event = event();
        event.setPayload(null);

        publisher.publish(event);

        verify(kafkaTemplate).send(any(ProducerRecord.class));
    }

    @Test
    void publishForResult_waitsForSendResult() throws Exception {
        org.apache.kafka.common.TopicPartition tp = new org.apache.kafka.common.TopicPartition("topic", 0);
        org.apache.kafka.clients.producer.RecordMetadata metadata =
                new org.apache.kafka.clients.producer.RecordMetadata(
                        tp, 0L, 0L, 0L, null, 0, 0);
        org.apache.kafka.clients.producer.ProducerRecord<String, String> record =
                new org.apache.kafka.clients.producer.ProducerRecord<>("topic", "key", "value");
        org.springframework.kafka.support.SendResult<String, String> sendResult =
                new org.springframework.kafka.support.SendResult<>(record, metadata);

        java.util.concurrent.CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> future =
                mock(java.util.concurrent.CompletableFuture.class);
        when(future.get(30, java.util.concurrent.TimeUnit.SECONDS)).thenReturn(sendResult);
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

        org.springframework.kafka.support.SendResult<String, String> result = publisher.publishForResult(event());

        assertNotNull(result);
        verify(future).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
}