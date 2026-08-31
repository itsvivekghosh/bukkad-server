package com.bhukkad.common.kafka;

import com.bhukkad.common.event.PlatformEventMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class KafkaPlatformEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    @Test
    void disabled_publishesNothing() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(false, "bhukkad.", "test-group"));

        publisher.publish(PlatformEventMessage.of("OrderCreated", "42", "{}"));

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void enabled_publishesToPrefixedTopic() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, "bhukkad.", "test-group"));

        publisher.publish(PlatformEventMessage.of("OrderCreated", "42", "{}"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(topic.capture(), key.capture(), value.capture());

        assertThat(topic.getValue()).isEqualTo("bhukkad.ordercreated");
        assertThat(key.getValue()).isEqualTo("42");
        assertThat(value.getValue()).contains("\"eventType\":\"OrderCreated\"");
    }

    @Test
    void publishFailure_isSwallowed() {
        KafkaPlatformEventPublisher publisher = new KafkaPlatformEventPublisher(
                kafkaTemplate, new KafkaProperties(true, "bhukkad.", "test-group"));
        org.mockito.Mockito.when(kafkaTemplate.send(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("broker down"));

        // Must not throw — the outbox retry path handles failures.
        publisher.publish(PlatformEventMessage.of("OrderCreated", "42", "{}"));
    }
}
