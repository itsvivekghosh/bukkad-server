package com.bhukkad.payment.domain.event;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.outbox.OutboxClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentEventPublisherSettlementRunTest {

    @Mock private OutboxClient outboxClient;

    private PaymentEventPublisher publisher() {
        return new PaymentEventPublisher(outboxClient);
    }

    @Test
    void settlementRunCompleted_enrichesOutboxWithRunTotals() {
        publisher().settlementRunCompleted(7L, LocalDate.of(2026, 8, 1), 3, 42,
                new BigDecimal("12345.67"));

        ArgumentCaptor<PlatformEventMessage> captor =
                ArgumentCaptor.forClass(PlatformEventMessage.class);
        verify(outboxClient).enqueue(captor.capture(), org.mockito.ArgumentMatchers.eq(7L));
        PlatformEventMessage message = captor.getValue();

        assertThat(message.eventType())
                .isEqualTo(PaymentEventPublisher.TYPE_SETTLEMENT_RUN_COMPLETED);
        assertThat(message.aggregateId()).isEqualTo("7");
        assertThat(message.payload())
                .contains("\"runId\":7")
                .contains("\"runDate\":\"2026-08-01\"")
                .contains("\"restaurantsSettled\":3")
                .contains("\"rowsSettled\":42")
                .contains("\"totalNet\":12345.67");
    }
}
