package com.bhukkad.order.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * PERF-2/D6 (G-1): an enqueue failure must PROPAGATE out of the publisher so
 * the caller's {@code @Transactional} method rolls back the order together
 * with the event (the old catch-log-swallow let orders commit with no event).
 */
@ExtendWith(MockitoExtension.class)
class OrderEventPublisherTest {

    @Mock
    private OutboxClient outboxClient;

    private OrderEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new OrderEventPublisher(outboxClient);
    }

    @Test
    void orderCreated_enqueuesEnvelope() {
        publisher.orderCreated(42L, 7L, 3L);

        verify(outboxClient).enqueue(any(PlatformEventMessage.class), anyLong());
    }

    @Test
    void enqueueFailure_propagatesSoBusinessTxRollsBack() {
        doThrow(new IllegalStateException("G-1 violation: outbox enqueue outside a business transaction"))
                .when(outboxClient).enqueue(any(PlatformEventMessage.class), anyLong());

        assertThatThrownBy(() -> publisher.orderStatusChanged(42L, "CONFIRMED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("G-1 violation");
    }
}
