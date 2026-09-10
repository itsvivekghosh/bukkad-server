package com.bhukkad.payment.consumer;

import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.idempotency.IdempotencyRecord;
import com.bhukkad.common.idempotency.IdempotencyRecordRepository;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.payment.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract for the ADR-001 {@code dispute_resolved} consumer: exactly-once
 * wallet credit via the (DISPUTE_CREDIT, disputeId) claim, deliberate skip of
 * other event types, poison handling WITHOUT burning the dedupe row, and
 * refundAmount parsed as a string decimal per the binding event contract.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeResolvedConsumerTest {

    private static final String PAYLOAD = """
            {"disputeId":7,"orderId":30,"customerId":2,"refundAmount":"150.00","reason":"late delivery"}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock private WalletService walletService;
    @Mock private IdempotencyRecordRepository idempotencyRecords;

    private DisputeResolvedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new DisputeResolvedConsumer(walletService, idempotencyRecords, objectMapper);
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), anyLong(), anyString(),
                isNull(), any())).thenReturn(1);
    }

    private String event(String payload) {
        // The listener consumes the SERIALIZED envelope (String), exactly as
        // the Kafka record/value deserializer delivers it.
        return PlatformEventMessage.of("dispute_resolved", "7", payload).toJson();
    }

    @Test
    void freshClaim_creditsWalletOnceWithDisputeReference() {
        consumer.onDisputeResolved(event(PAYLOAD));

        verify(idempotencyRecords).insertIfAbsent(
                eq("7"), eq(DisputeResolvedConsumer.SCOPE_DISPUTE_CREDIT), eq(2L),
                eq(IdempotencyRecord.IdempotencyStatus.COMPLETED.name()), isNull(), any());
        verify(walletService).credit(2L, new BigDecimal("150.00"), "DISPUTE-7");
    }

    @Test
    void replayedDispute_isSkippedWithoutSecondCredit() {
        when(idempotencyRecords.insertIfAbsent(anyString(), anyString(), anyLong(), anyString(),
                isNull(), any())).thenReturn(0);

        consumer.onDisputeResolved(event(PAYLOAD));

        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void otherEventTypes_areDeliberatelySkipped() {
        var orderEvent = PlatformEventMessage.of(
                "OrderStatusChanged", "30", "{\"orderId\":30}");

        consumer.onDisputeResolved(orderEvent.toJson());

        verify(walletService, never()).credit(anyLong(), any(), anyString());
        verify(idempotencyRecords, never()).insertIfAbsent(
                anyString(), anyString(), anyLong(), anyString(), isNull(), any());
    }

    @Test
    void nonPositiveRefundAmount_isPoisonWithoutBurningTheClaim() {
        String badAmount = """
                {"disputeId":7,"orderId":30,"customerId":2,"refundAmount":"0.00","reason":"x"}
                """;

        assertThatThrownBy(() -> consumer.onDisputeResolved(event(badAmount)))
                .isInstanceOf(PoisonEventException.class);
        verify(idempotencyRecords, never()).insertIfAbsent(
                anyString(), anyString(), anyLong(), anyString(), isNull(), any());
        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void nonNumericRefundAmount_isPoison() {
        String badAmount = """
                {"disputeId":7,"orderId":30,"customerId":2,"refundAmount":"abc","reason":"x"}
                """;

        assertThatCode(() -> consumer.onDisputeResolved(event(badAmount)))
                .isInstanceOf(PoisonEventException.class);
        verify(walletService, never()).credit(anyLong(), any(), anyString());
    }

    @Test
    void missingDisputeId_isPoison() {
        String missing = """
                {"orderId":30,"customerId":2,"refundAmount":"150.00"}
                """;

        assertThatThrownBy(() -> consumer.onDisputeResolved(event(missing)))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("disputeId");
    }
}
