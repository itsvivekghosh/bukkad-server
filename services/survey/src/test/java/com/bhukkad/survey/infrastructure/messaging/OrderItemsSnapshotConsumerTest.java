package com.bhukkad.survey.infrastructure.messaging;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.survey.domain.repository.TrendingDishRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the trending-dish materializer: listens on the ORDER
 * domain's {@code order.events.v1} stream, dedupes on the envelope eventId
 * (KAFKA_CONSUME claim, AdminCqrs pattern), and routes malformed records to
 * the container's DefaultErrorHandler via {@link PoisonEventException}
 * instead of swallowing them (V-10).
 */
@ExtendWith(MockitoExtension.class)
class OrderItemsSnapshotConsumerTest {

    @Mock
    private TrendingDishRepository trendingDishRepository;

    @Mock
    private IdempotencyRecordRepository idempotencyRecords;

    private OrderItemsSnapshotConsumer consumer;

    @BeforeEach
    void initConsumer() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new OrderItemsSnapshotConsumer(trendingDishRepository, mapper, idempotencyRecords);
    }

    /** The consumer must subscribe to the stream the order domain publishes to. */
    @Test
    void listensOnTheOrderDomainStream() {
        String topic = null;
        for (Method method : OrderItemsSnapshotConsumer.class.getDeclaredMethods()) {
            KafkaListener listener = method.getAnnotation(KafkaListener.class);
            if (listener != null) {
                topic = listener.topics()[0];
            }
        }
        assertThat(topic)
                .as("OrderItemsSnapshotConsumer @KafkaListener topic")
                .isEqualTo("order.events.v1");
    }

    @Test
    void snapshotEvent_upsertsAllValidItems() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of("ORDER_ITEMS_SNAPSHOT", "42",
                "{\"orderId\":42,\"restaurantId\":10,"
                        + "\"items\":[{\"menuItemId\":1,\"name\":\"Margherita\",\"quantity\":2},"
                        + "{\"menuItemId\":2,\"name\":\"Pepsi\",\"quantity\":1}],"
                        + "\"orderedAt\":\"2026-09-11T10:15:30\"}");

        consumer.onPlatformEvent(event.toJson());

        verify(trendingDishRepository).upsert(eq(1L), eq(10L), eq("Margherita"), eq(2L),
                eq(LocalDateTime.parse("2026-09-11T10:15:30")));
        verify(trendingDishRepository).upsert(eq(2L), eq(10L), eq("Pepsi"), eq(1L), any());
        verify(idempotencyRecords).insertIfAbsent(eq(event.eventId()),
                eq(IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME.name()),
                isNull(),
                eq(IdempotencyRecord.IdempotencyStatus.COMPLETED.name()),
                isNull(), any());
    }

    @Test
    void snapshotEvent_skipsInvalidItemLinesButProjectsTheRest() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of("ORDER_ITEMS_SNAPSHOT", "42",
                "{\"orderId\":42,\"restaurantId\":10,"
                        + "\"items\":[{\"menuItemId\":0,\"name\":\"broken\",\"quantity\":1},"
                        + "{\"menuItemId\":3,\"name\":\"Fries\",\"quantity\":1}]}");

        consumer.onPlatformEvent(event.toJson());

        // Deliberate per-line skip: only the valid line is projected.
        verify(trendingDishRepository, never()).upsert(eq(0L), anyLong(), anyString(), anyLong(), any());
        verify(trendingDishRepository).upsert(eq(3L), eq(10L), eq("Fries"), eq(1L), any());
    }

    @Test
    void otherEventTypes_areSkippedWithoutBurningAClaim() {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"orderId\":42}");

        consumer.onPlatformEvent(event.toJson());

        verifyNoInteractions(trendingDishRepository);
        verifyNoInteractions(idempotencyRecords);
    }

    /** V-10: a malformed envelope is poison — DefaultErrorHandler → DLT, never swallowed. */
    @Test
    void malformedEnvelope_throwsPoisonEventException() {
        PoisonEventException thrown = assertThrows(PoisonEventException.class,
                () -> consumer.onPlatformEvent("{not valid json}"));

        assertEquals("Malformed event envelope", thrown.getMessage());
        verifyNoInteractions(trendingDishRepository);
        verifyNoInteractions(idempotencyRecords);
    }

    /** A tracked snapshot with an unparsable payload is poison and burns no dedupe row. */
    @Test
    void snapshotWithMalformedPayload_throwsPoisonEventExceptionWithoutBurningClaim() {
        PlatformEventMessage event = PlatformEventMessage.of(
                "ORDER_ITEMS_SNAPSHOT", "42", "{not valid payload}");

        PoisonEventException thrown = assertThrows(PoisonEventException.class,
                () -> consumer.onPlatformEvent(event.toJson()));

        assertEquals("Unparsable ORDER_ITEMS_SNAPSHOT payload: eventId=" + event.eventId(),
                thrown.getMessage());
        verifyNoInteractions(trendingDishRepository);
        verifyNoInteractions(idempotencyRecords);
    }

    /** A snapshot without a dedup token cannot be claimed — poison, not double-count. */
    @Test
    void snapshotWithoutEventId_throwsPoisonEventException() {
        PlatformEventMessage event = new PlatformEventMessage(
                null, "ORDER_ITEMS_SNAPSHOT", 1, null, "42", null, null,
                "{\"orderId\":42,\"restaurantId\":10,\"items\":[]}");

        assertThrows(PoisonEventException.class, () -> consumer.onPlatformEvent(event.toJson()));

        verifyNoInteractions(trendingDishRepository);
        verifyNoInteractions(idempotencyRecords);
    }

    /** V-12: a redelivered eventId (claim lost) must not double-count dishes. */
    @Test
    void duplicateEventId_claimLost_skipsProjection() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(0);
        PlatformEventMessage event = PlatformEventMessage.of("ORDER_ITEMS_SNAPSHOT", "42",
                "{\"orderId\":42,\"restaurantId\":10,"
                        + "\"items\":[{\"menuItemId\":1,\"name\":\"Margherita\",\"quantity\":2}]}");

        consumer.onPlatformEvent(event.toJson());

        verifyNoInteractions(trendingDishRepository);
    }

    /** The listener method is transactional so the claim and the upserts commit atomically. */
    @Test
    void listenerIsTransactional() throws Exception {
        Method listener = List.of(OrderItemsSnapshotConsumer.class.getDeclaredMethods()).stream()
                .filter(m -> m.getAnnotation(KafkaListener.class) != null)
                .findFirst()
                .orElseThrow();
        assertThat(listener.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .as("projection + claim must share one transaction")
                .isNotNull();
    }
}
