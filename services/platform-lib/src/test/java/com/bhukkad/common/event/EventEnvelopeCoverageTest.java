package com.bhukkad.common.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bhukkad.common.config.ExternalEventsProperties;
import com.bhukkad.common.kafka.KafkaPlatformEventPublisher;
import com.bhukkad.common.outbox.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Domain event records, the Kafka envelope and the (deprecated) external event
 * bridge gating.
 */
@SuppressWarnings("deprecation")
class EventEnvelopeCoverageTest {

    @Test
    void domain_events_projectAggregateTypeAndPayload() {
        Instant at = Instant.parse("2026-09-12T10:00:00Z");

        OrderEvents.OrderCreated order = new OrderEvents.OrderCreated(10L, 20L, 30L, 259.5, at);
        assertThat(order.aggregateId()).isEqualTo(10L);
        assertThat(order.eventType()).isEqualTo("OrderCreated");
        assertThat(order.payload()).isSameAs(order);
        assertThat(order).isEqualTo(new OrderEvents.OrderCreated(10L, 20L, 30L, 259.5, at));
        assertThat(order.toString()).contains("orderId=10");

        assertThat(new OrderEvents.OrderStatusUpdated(10L, "PREPARING", at).eventType())
                .isEqualTo("OrderStatusUpdated");
        assertThat(new OrderEvents.OrderStatusUpdated(10L, "PREPARING", at).aggregateId()).isEqualTo(10L);
        assertThat(new OrderEvents.OrderCancelled(10L, "user", at).eventType()).isEqualTo("OrderCancelled");
        assertThat(new OrderEvents.OrderCancelled(10L, "user", at).aggregateId()).isEqualTo(10L);

        PaymentEvents.PaymentCompleted completed =
                new PaymentEvents.PaymentCompleted(1L, 10L, 259.5, "UPI", at);
        assertThat(completed.aggregateId()).isEqualTo(10L); // projected on the ORDER aggregate
        assertThat(completed.eventType()).isEqualTo("PaymentCompleted");
        assertThat(completed.payload()).isSameAs(completed);

        PaymentEvents.PaymentFailed failed = new PaymentEvents.PaymentFailed(1L, 10L, "declined", at);
        assertThat(failed.aggregateId()).isEqualTo(10L);
        assertThat(failed.eventType()).isEqualTo("PaymentFailed");

        RestaurantEvents.RestaurantCreated restaurant =
                new RestaurantEvents.RestaurantCreated(5L, 2L, "Masala", at);
        assertThat(restaurant.aggregateId()).isEqualTo(5L);
        assertThat(restaurant.eventType()).isEqualTo("RestaurantCreated");
        assertThat(restaurant.payload()).isSameAs(restaurant);

        RestaurantEvents.MenuItemUpdated item =
                new RestaurantEvents.MenuItemUpdated(6L, 5L, 99.0, Boolean.TRUE, at);
        assertThat(item.aggregateId()).isEqualTo(6L);
        assertThat(item.eventType()).isEqualTo("MenuItemUpdated");

        DisputeEvents.DisputeResolved dispute =
                new DisputeEvents.DisputeResolved(7L, 10L, 20L, 100.0, "refund", at);
        assertThat(dispute.aggregateId()).isEqualTo(7L);
        assertThat(dispute.eventType()).isEqualTo("DisputeResolved");
        assertThat(dispute.payload()).isSameAs(dispute);
    }

    @Test
    void platformEventMessage_factoriesJsonAndValidation() {
        PlatformEventMessage msg = PlatformEventMessage.of("OrderCreated", "42", "{\"a\":1}");
        assertThat(msg.eventId()).isNotBlank();
        assertThat(msg.correlationId()).isEqualTo(msg.eventId());
        assertThat(msg.schemaVersion()).isEqualTo(1);
        assertThat(msg.traceparent()).isNull();
        assertThat(msg.occurredAt()).isBefore(Instant.now().plusSeconds(5));

        PlatformEventMessage correlated = PlatformEventMessage.of("OrderCreated", "42", "corr-7", "{}");
        assertThat(correlated.correlationId()).isEqualTo("corr-7");

        PlatformEventMessage roundTrip = PlatformEventMessage.fromJson(msg.toJson());
        assertThat(roundTrip).isEqualTo(msg);

        assertThatThrownBy(() -> PlatformEventMessage.fromJson("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid PlatformEventMessage JSON");
        assertThatThrownBy(() -> new PlatformEventMessage("id", " ", 1, Instant.now(), "a", "c", null, "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType must not be blank");
        assertThatThrownBy(() -> new PlatformEventMessage("id", "t", 1, Instant.now(), "a", "c", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload must not be null");
    }

    private static OutboxEvent outboxRecord() {
        OutboxEvent event = new OutboxEvent();
        event.setEventType("OrderCreated");
        event.setAggregateType("ORDER");
        event.setAggregateId(99L);
        event.setPayload("{\"orderId\":99}");
        event.setStatus(OutboxEvent.OutboxStatus.PENDING);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaPlatformEventPublisher> provider(KafkaPlatformEventPublisher pub) {
        ObjectProvider<KafkaPlatformEventPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(pub);
        org.mockito.Mockito.doAnswer(invocation -> {
            if (pub != null) {
                invocation.<Consumer<KafkaPlatformEventPublisher>>getArgument(0).accept(pub);
            }
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    @Test
    void externalEventBridge_gatesOnPipelineState() {
        KafkaPlatformEventPublisher publisher = mock(KafkaPlatformEventPublisher.class);
        ExternalEventsProperties props = new ExternalEventsProperties();
        OutboxEvent event = outboxRecord();

        ExternalEventBridge off = new ExternalEventBridge(props, provider(publisher));
        off.forward(event);
        off.forwardForResult(event);
        verify(publisher, never()).publish(any());

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        ch.qos.logback.classic.Logger logger = context.getLogger(ExternalEventBridge.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> logCapture = new ListAppender<>();
        logCapture.start();
        logger.addAppender(logCapture);
        logger.setLevel(Level.INFO);
        try {
            props.setEnabled(true); // type=log: log-only path, still no kafka publish
            off.forward(event);
            off.forwardForResult(event); // falls back to fire-and-forget
            verify(publisher, never()).publish(any());
            assertThat(logCapture.list).isNotEmpty();
            assertThat(logCapture.list.get(0).getFormattedMessage())
                    .contains("EXTERNAL_EVENT")
                    .contains("aggregateId=99");
        } finally {
            logger.detachAppender(logCapture);
            logger.setLevel(previous);
        }

        props.setType("kafka");
        when(publisher.publishForResult(any())).thenReturn(true);
        ExternalEventBridge bridge = new ExternalEventBridge(props, provider(publisher));

        bridge.forward(event);
        ArgumentCaptor<PlatformEventMessage> envelope = ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(publisher).publish(envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo("OrderCreated");
        assertThat(envelope.getValue().aggregateId()).isEqualTo("99");
        assertThat(envelope.getValue().payload()).isEqualTo("{\"orderId\":99}");

        bridge.forwardForResult(event);
        verify(publisher).publishForResult(any(PlatformEventMessage.class));

        // failed ack surfaces as IllegalStateException for the poller retry
        when(publisher.publishForResult(any())).thenReturn(false);
        assertThatThrownBy(() -> bridge.forwardForResult(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to publish event to Kafka");
    }

    @Test
    void externalEventBridge_withoutKafkaPublisherBean_staysSilent() {
        ExternalEventsProperties props = new ExternalEventsProperties();
        props.setEnabled(true);
        props.setType("kafka");
        ExternalEventBridge bridge = new ExternalEventBridge(props, provider(null));
        bridge.forward(outboxRecord());      // envelope built, no publisher — no-op
        bridge.forwardForResult(outboxRecord());
    }

    @Test
    void noOpEventPublisher_isAlwaysAvailable() {
        NoOpEventPublisher publisher = new NoOpEventPublisher();
        publisher.publish(new OrderEvents.OrderCreated(1L, 2L, 3L, 10.0, Instant.now()));
    }
}
