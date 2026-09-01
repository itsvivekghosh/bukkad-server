package com.bhukkad.event;

import com.bhukkad.config.ExternalEventsProperties;
import com.bhukkad.event.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.outbox.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link ExternalEventBridge}.
 *
 * <p>The bridge has three routing outcomes: disabled (no-op), Kafka enabled
 * (forward to the publisher when one is available), and log fallback. The
 * {@link ObjectProvider} is mocked so both the "bean available" and "bean
 * absent" resolutions are exercised without a Spring context.
 */
@ExtendWith(MockitoExtension.class)
class ExternalEventBridgeTest {

    @Mock
    private ObjectProvider<KafkaPlatformEventPublisher> kafkaPublisherProvider;
    @Mock
    private KafkaPlatformEventPublisher kafkaPublisher;

    private final ExternalEventsProperties properties = new ExternalEventsProperties();

    private ExternalEventBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new ExternalEventBridge(properties, kafkaPublisherProvider);
    }

    @Test
    void forward_whenDisabled_returnsWithoutTouchingKafka() {
        properties.setEnabled(false);
        properties.setType("kafka");

        assertDoesNotThrow(() -> bridge.forward(outboxEvent()));

        verifyNoInteractions(kafkaPublisherProvider);
    }

    @Test
    void forward_whenKafkaEnabledAndPublisherAvailable_publishesEvent() {
        properties.setEnabled(true);
        properties.setType("kafka");
        doAnswer(invocation -> {
            Consumer<KafkaPlatformEventPublisher> consumer = invocation.getArgument(0);
            consumer.accept(kafkaPublisher);
            return null;
        }).when(kafkaPublisherProvider).ifAvailable(any());

        OutboxEvent event = outboxEvent();
        bridge.forward(event);

        verify(kafkaPublisher).publish(event);
        verify(kafkaPublisherProvider).ifAvailable(any());
    }

    @Test
    void forward_whenKafkaEnabledButNoPublisherAvailable_doesNotThrowOrPublish() {
        properties.setEnabled(true);
        properties.setType("kafka");
        // Unstubbed mock provider resolves no bean: ifAvailable is a silent no-op.

        OutboxEvent event = outboxEvent();
        assertDoesNotThrow(() -> bridge.forward(event));

        verifyNoInteractions(kafkaPublisher);
    }

    @Test
    void forward_whenLogFallbackEnabled_doesNotConsultKafkaProvider() {
        properties.setEnabled(true);
        properties.setType("log");

        assertDoesNotThrow(() -> bridge.forward(outboxEvent()));

        verifyNoInteractions(kafkaPublisherProvider);
    }

    @Test
    void forwardForResult_whenKafkaEnabledAndPublisherAvailable_publishesAndReturnsResult() throws Exception {
        properties.setEnabled(true);
        properties.setType("kafka");
        doAnswer(invocation -> {
            Consumer<KafkaPlatformEventPublisher> consumer = invocation.getArgument(0);
            consumer.accept(kafkaPublisher);
            return null;
        }).when(kafkaPublisherProvider).ifAvailable(any());

        OutboxEvent event = outboxEvent();
        assertDoesNotThrow(() -> bridge.forwardForResult(event));

        verify(kafkaPublisher).publishForResult(event);
        verify(kafkaPublisherProvider).ifAvailable(any());
    }

    @Test
    void forwardForResult_whenKafkaEnabledButNoPublisherAvailable_doesNotThrow() {
        properties.setEnabled(true);
        properties.setType("kafka");
        // Unstubbed mock provider resolves no bean: ifAvailable is a silent no-op.

        assertDoesNotThrow(() -> bridge.forwardForResult(outboxEvent()));

        verifyNoInteractions(kafkaPublisher);
    }

    @Test
    void forwardForResult_whenLogFallbackEnabled_delegatesToForward() {
        properties.setEnabled(true);
        properties.setType("log");

        OutboxEvent event = outboxEvent();
        assertDoesNotThrow(() -> bridge.forwardForResult(event));

        verifyNoInteractions(kafkaPublisher);
    }

    @Test
    void forwardForResult_whenDisabled_returnsWithoutPublishing() {
        properties.setEnabled(false);
        properties.setType("kafka");

        assertDoesNotThrow(() -> bridge.forwardForResult(outboxEvent()));

        verifyNoInteractions(kafkaPublisherProvider);
        verifyNoInteractions(kafkaPublisher);
    }

    private OutboxEvent outboxEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("ORDER_PLACED");
        event.setAggregateType("ORDER");
        event.setAggregateId(15L);
        event.setPayload("{\"orderId\":15}");
        return event;
    }
}
