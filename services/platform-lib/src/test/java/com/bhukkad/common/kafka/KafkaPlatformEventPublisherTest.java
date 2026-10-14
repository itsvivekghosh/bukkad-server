package com.bhukkad.common.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KafkaPlatformEventPublisherTest {

    private static final String BASE_TOPIC = "bhukkad.platform.events";

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    void disabled_publishesNothing() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(false, BASE_TOPIC, "test-group"));

        publisher.publish(PlatformEventMessage.of("OrderCreated", "42", "{}"));

        verifyNoInteractions(kafkaTemplate);
    }

    /**
     * Publisher/consumer topic parity (audit-critical): the publisher writes to
     * the single BASE platform topic — consumers subscribe to the same base
     * topic and dispatch on the eventType inside the envelope. The previous
     * per-type suffix ("&lt;base&gt;.&lt;eventType-lowercase&gt;") produced
     * topics no listener consumes and the Redpanda topics-job never seeds.
     */
    @Test
    void enabled_publishesToBaseTopicWithoutPerTypeSuffix() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, BASE_TOPIC, "test-group"));

        publisher.publish(PlatformEventMessage.of("OrderCreated", "42", "{}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topic.capture(), key.capture(), value.capture());

        assertThat(topic.getValue()).isEqualTo("bhukkad.platform.events");
        assertThat(topic.getValue()).doesNotContain("ordercreated");
        assertThat(key.getValue()).isEqualTo("42");
        assertThat(value.getValue()).contains("\"eventType\":\"OrderCreated\"");
    }

    @Test
    void publishForResult_writesSameBaseTopicAsPublish() {
        org.apache.kafka.clients.producer.RecordMetadata meta =
                new org.apache.kafka.clients.producer.RecordMetadata(
                        new org.apache.kafka.common.TopicPartition(BASE_TOPIC, 0),
                        0L, 0, 0L, 0, 0);
        org.springframework.kafka.support.SendResult<String, String> sendResult =
                new org.springframework.kafka.support.SendResult<>(null, meta);
        
        // Mock executeInTransaction to capture the callback and invoke it with a mocked operations
        org.mockito.Mockito.when(kafkaTemplate.executeInTransaction(
                org.mockito.ArgumentMatchers.<org.springframework.kafka.core.KafkaOperations.OperationsCallback<String, String, SendResult<String, String>>>any()))
                .thenAnswer(invocation -> {
                    org.springframework.kafka.core.KafkaOperations.OperationsCallback<String, String, SendResult<String, String>> callback = 
                            invocation.getArgument(0);
                    // Create a mock operations object
                    org.springframework.kafka.core.KafkaOperations<String, String> mockOps = mock(org.springframework.kafka.core.KafkaOperations.class);
                    org.mockito.Mockito.when(mockOps.send(org.mockito.ArgumentMatchers.anyString(), 
                            org.mockito.ArgumentMatchers.anyString(), 
                            org.mockito.ArgumentMatchers.anyString()))
                            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(sendResult));
                    return callback.doInOperations(mockOps);
                });

        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, BASE_TOPIC, "test-group"),
                java.time.Duration.ofSeconds(10));

        assertThat(publisher.publishForResult(PlatformEventMessage.of("OrderStatusChanged", "42", "{}")))
                .isTrue();
    }

    @Test
    void publishFailure_isSwallowed() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, BASE_TOPIC, "test-group"));
        org.mockito.Mockito.when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("broker down"));

        // Must not throw — the outbox retry path handles failures.
        publisher.publish(PlatformEventMessage.of("OrderCreated", "42", "{}"));
    }

    /**
     * PERF-2/B2 (fail-first rewrite): the disabled publisher must report
     * FALSE, not the old "no-op success". Returning true let the relay flip
     * outbox rows to PUBLISHED without anything reaching the broker — the
     * silent event-loss blackhole.
     */
    @Test
    void publishForResult_disabled_returnsFalse() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(false, BASE_TOPIC, "test-group"));

        boolean result = publisher.publishForResult(PlatformEventMessage.of("OrderCreated", "42", "{}"));

        assertThat(result).isFalse();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void publishForResult_ackedByBroker_returnsTrue() {
        // kafka-clients 3.6.x: RecordMetadata(TopicPartition, offset, batchLength(int),
        // lastRecordValue, keySize, valueSize) — partition/key come from the TopicPartition.
        org.apache.kafka.clients.producer.RecordMetadata meta =
                new org.apache.kafka.clients.producer.RecordMetadata(
                        new org.apache.kafka.common.TopicPartition(BASE_TOPIC, 0),
                        0L, 0, 0L, 0, 0);
        org.springframework.kafka.support.SendResult<String, String> sendResult =
                new org.springframework.kafka.support.SendResult<>(null, meta);
        
        org.mockito.Mockito.when(kafkaTemplate.executeInTransaction(
                org.mockito.ArgumentMatchers.<org.springframework.kafka.core.KafkaOperations.OperationsCallback<String, String, SendResult<String, String>>>any()))
                .thenAnswer(invocation -> {
                    org.springframework.kafka.core.KafkaOperations.OperationsCallback<String, String, SendResult<String, String>> callback = 
                            invocation.getArgument(0);
                    org.springframework.kafka.core.KafkaOperations<String, String> mockOps = mock(org.springframework.kafka.core.KafkaOperations.class);
                    org.mockito.Mockito.when(mockOps.send(org.mockito.ArgumentMatchers.anyString(), 
                            org.mockito.ArgumentMatchers.anyString(), 
                            org.mockito.ArgumentMatchers.anyString()))
                            .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(sendResult));
                    return callback.doInOperations(mockOps);
                });

        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, BASE_TOPIC, "test-group"),
                java.time.Duration.ofSeconds(10));

        assertThat(publisher.publishForResult(PlatformEventMessage.of("OrderCreated", "42", "{}")))
                .isTrue();
    }

    @Test
    void publishForResult_brokerException_returnsFalse() {
        org.mockito.Mockito.when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("broker down"));

        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, BASE_TOPIC, "test-group"),
                java.time.Duration.ofSeconds(10));

        boolean result = publisher.publishForResult(PlatformEventMessage.of("OrderCreated", "42", "{}"));
        assertThat(result).isFalse();
    }
}
