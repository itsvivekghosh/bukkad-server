package com.bhukkad.realtime.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.realtime.dto.LiveUpdateEvent;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLiveEventConsumerTest {

    @Mock
    private OrderLiveRelay relay;

    @Mock
    private IdempotencyRecordRepository idempotencyRecords;

    private OrderLiveEventConsumer consumer;

    @BeforeEach
    void initConsumer() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        // Real TransactionTemplate over a no-op manager (NotificationEventConsumerTest
        // precedent): the claim callback runs, the repository mock decides the outcome.
        consumer = new OrderLiveEventConsumer(relay, mapper, idempotencyRecords,
                new TransactionTemplate(new NoOpTxManager()));
    }

    @Test
    void consumesOrderStatusChanged_relaysLiveUpdateEvent() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42",
                "{\"orderId\":42,\"status\":\"DELIVERED\"}");

        consumer.onOrderEvent(event.toJson());

        ArgumentCaptor<LiveUpdateEvent> captor = ArgumentCaptor.forClass(LiveUpdateEvent.class);
        verify(relay).relay(captor.capture());

        LiveUpdateEvent live = captor.getValue();
        assertEquals("OrderStatusChanged", live.getType());
        OrderLiveUpdate update = (OrderLiveUpdate) live.getPayload();
        assertEquals(OrderLiveUpdate.EventType.STATUS_CHANGED, update.getEventType());
        assertEquals(42L, update.getOrderId());
        assertEquals("DELIVERED", update.getStatus());
        assertNull(update.getEventId());
    }

    @Test
    void consumesOrderCreated_relaysKitchenAndCustomerFields() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42",
                "{\"orderId\":42,\"customerId\":7,\"restaurantId\":10}");

        consumer.onOrderEvent(event.toJson());

        ArgumentCaptor<LiveUpdateEvent> captor = ArgumentCaptor.forClass(LiveUpdateEvent.class);
        verify(relay).relay(captor.capture());

        LiveUpdateEvent live = captor.getValue();
        assertEquals("OrderCreated", live.getType());
        OrderLiveUpdate update = (OrderLiveUpdate) live.getPayload();
        assertEquals(OrderLiveUpdate.EventType.ORDER_CREATED, update.getEventType());
        assertEquals(42L, update.getOrderId());
        assertEquals(7L, update.getCustomerId());
        assertEquals(10L, update.getRestaurantId());
        assertEquals("PLACED", update.getStatus());
    }

    @Test
    void consumesOrderCreated_claimsKafkaConsumeEventIdBeforeRelaying() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"orderId\":42}");

        consumer.onOrderEvent(event.toJson());

        // KAFKA_CONSUME-scoped eventId claim (Notification pattern) before the relay.
        verify(idempotencyRecords).insertIfAbsent(eq(event.eventId()),
                eq(IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME.name()),
                isNull(),
                eq(IdempotencyRecord.IdempotencyStatus.COMPLETED.name()),
                isNull(), any());
        verify(relay).relay(any());
    }

    @Test
    void ignoredEventType_isNotRelayedAndBurnsNoDedupeRow() {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCancelled", "42", "{\"orderId\":42}");

        consumer.onOrderEvent(event.toJson());

        verifyNoInteractions(relay);
        verifyNoInteractions(idempotencyRecords);
    }

    /**
     * V-10: a malformed envelope is POISON — it must reach the container's
     * DefaultErrorHandler (retry → DLT), never be swallowed.
     */
    @Test
    void malformedEnvelope_throwsPoisonEventExceptionWithoutRelay() {
        PoisonEventException thrown = assertThrows(PoisonEventException.class,
                () -> consumer.onOrderEvent("{not valid json}"));

        assertEquals("Malformed event envelope", thrown.getMessage());
        verifyNoInteractions(relay);
        verifyNoInteractions(idempotencyRecords);
    }

    /** V-10/V-22: a tracked event with an unparsable payload is poison, not silence. */
    @Test
    void trackedEventWithMalformedPayload_throwsPoisonEventExceptionWithoutBurningClaim() {
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{not valid payload}");

        PoisonEventException thrown = assertThrows(PoisonEventException.class,
                () -> consumer.onOrderEvent(event.toJson()));

        assertEquals("Unparsable OrderCreated payload: eventId=" + event.eventId(),
                thrown.getMessage());
        verifyNoInteractions(relay);
        verifyNoInteractions(idempotencyRecords);
    }

    /** No dedup token at all is poison — a replay would double fan-out. */
    @Test
    void trackedEventWithoutEventId_throwsPoisonEventException() {
        PlatformEventMessage event = new PlatformEventMessage(
                null, "OrderCreated", 1, null, "42", null, null, "{\"orderId\":42}");

        assertThrows(PoisonEventException.class, () -> consumer.onOrderEvent(event.toJson()));

        verifyNoInteractions(relay);
        verifyNoInteractions(idempotencyRecords);
    }

    /** At-least-once redelivery: a lost claim race must not fan out twice. */
    @Test
    void duplicateEventId_claimLost_skipsRelay() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(0);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderStatusChanged", "42", "{\"orderId\":42,\"status\":\"DELIVERED\"}");

        consumer.onOrderEvent(event.toJson());

        verifyNoInteractions(relay);
    }

    /**
     * Relay failure: the claim is released and the exception propagates so the
     * DefaultErrorHandler routes the record to the DLT for replay.
     */
    @Test
    void relayFailure_releasesClaimAndRethrows() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), any(), anyString(),
                any(), any())).thenReturn(1);
        PlatformEventMessage event = PlatformEventMessage.of(
                "OrderCreated", "42", "{\"orderId\":42}");
        doThrow(new IllegalStateException("redis down")).when(relay).relay(any());

        assertThrows(IllegalStateException.class, () -> consumer.onOrderEvent(event.toJson()));

        verify(idempotencyRecords).deleteByScopeAndIdempotencyKey(
                eq(IdempotencyRecord.IdempotencyScope.KAFKA_CONSUME), eq(event.eventId()));
    }

    /** No-op manager so the real TransactionTemplate's claim tx runs inline. */
    private static final class NoOpTxManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction,
                               org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
