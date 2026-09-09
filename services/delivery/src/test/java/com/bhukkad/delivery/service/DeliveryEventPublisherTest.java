package com.bhukkad.delivery.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Delivery lifecycle events funnel into the transactional outbox; a failed
 * enqueue must never break the caller's business transaction path.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryEventPublisherTest {

    @Mock private OutboxClient outboxClient;
    @InjectMocks private DeliveryEventPublisher publisher;

    @Test
    void deliveryAssigned_enqueuesAssignmentEvent() {
        publisher.deliveryAssigned(42L, 5L);

        ArgumentCaptor<PlatformEventMessage> captor =
                ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(42L));
        PlatformEventMessage message = captor.getValue();
        assertThat(message.eventType()).isEqualTo(DeliveryEventPublisher.TYPE_ASSIGNED);
        assertThat(message.aggregateId()).isEqualTo("42");
        assertThat(message.payload()).isEqualTo("{\"orderId\":42,\"agentId\":5}");
    }

    @Test
    void orderDelivered_enqueuesDeliveredEvent() {
        publisher.orderDelivered(7L, 3L);

        ArgumentCaptor<PlatformEventMessage> captor =
                ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(7L));
        assertThat(captor.getValue().eventType()).isEqualTo(DeliveryEventPublisher.TYPE_DELIVERED);
        assertThat(captor.getValue().payload()).isEqualTo("{\"orderId\":7,\"agentId\":3}");
    }

    @Test
    void outboxFailure_isSwallowedNotPropagated() {
        doThrow(new RuntimeException("outbox down"))
                .when(outboxClient).enqueue(any(PlatformEventMessage.class), any());

        assertThatCode(() -> publisher.deliveryAssigned(1L, 2L))
                .doesNotThrowAnyException();
    }
}
