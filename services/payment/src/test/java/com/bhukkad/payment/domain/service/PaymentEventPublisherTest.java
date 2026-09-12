package com.bhukkad.payment.domain.service;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.bhukkad.payment.domain.event.PaymentEventPublisher;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherTest {

    @Mock private OutboxClient outboxClient;
    @InjectMocks private PaymentEventPublisher publisher;

    @Test
    void paymentSettled_enqueuesEventWithAmount() {
        publisher.paymentSettled(1L, 10L, 2L, new BigDecimal("150.00"));

        ArgumentCaptor<PlatformEventMessage> captor = ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(1L));
        PlatformEventMessage message = captor.getValue();
        assertThat(message.eventType()).isEqualTo("PaymentSettled");
        assertThat(message.aggregateId()).isEqualTo("1");
        assertThat(message.payload())
                .contains("\"paymentId\":1", "\"orderId\":10", "\"customerId\":2", "\"amount\":150.00");
    }

    @Test
    void walletCredited_enqueuesEventWithTransactionId() {
        publisher.walletCredited(2L, new BigDecimal("75.00"), 33L);

        ArgumentCaptor<PlatformEventMessage> captor = ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), eq(2L));
        PlatformEventMessage message = captor.getValue();
        assertThat(message.eventType()).isEqualTo("WalletCredited");
        assertThat(message.payload())
                .contains("\"customerId\":2", "\"amount\":75.00", "\"transactionId\":33");
    }

    @Test
    void enqueue_outboxFailure_doesNotPropagate() {
        doThrow(new IllegalStateException("outbox down")).when(outboxClient)
                .enqueue(any(PlatformEventMessage.class), any(Long.class));

        assertThatCode(() -> publisher.paymentSettled(1L, 10L, 2L, new BigDecimal("10.00")))
                .doesNotThrowAnyException();
    }
}
