package com.bhukkad.payment.infrastructure.messaging;

import com.bhukkad.common.error.PaymentGatewayException;
import com.bhukkad.common.event.PlatformEventMessage;
import com.bhukkad.common.kafka.PoisonEventException;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentRequestedConsumerTest {

    @Mock private PaymentService paymentService;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private PaymentRequestedConsumer consumer;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        consumer = new PaymentRequestedConsumer(paymentService, objectMapper);
    }

    private String envelope(String eventType, String payloadJson) throws Exception {
        return objectMapper.writeValueAsString(new PlatformEventMessage(
                "evt-1", eventType, 1, java.time.Instant.now(), "agg-1", "corr-1", null, payloadJson));
    }

    private static String goodPayload() {
        return """
                {"orderId":10,"customerId":20,"amount":"250.50","currency":"INR","idempotencyKey":"key-1"}""";
    }

    @Test
    void happyPath_chargesThroughService() throws Exception {
        when(paymentService.processPaymentRequested(10L, 20L, new BigDecimal("250.50"), "INR", "key-1"))
                .thenReturn(settledPayment());

        assertThatCode(() -> consumer.onPaymentRequested(
                envelope("payment_requested", goodPayload()))).doesNotThrowAnyException();

        verify(paymentService).processPaymentRequested(10L, 20L, new BigDecimal("250.50"), "INR", "key-1");
    }

    @Test
    void otherEventTypes_areSkipped() throws Exception {
        consumer.onPaymentRequested(envelope("order_cancelled", "{}"));

        verify(paymentService, never()).processPaymentRequested(any(), any(), any(), any(), any());
    }

    @Test
    void gatewayDecline_isTerminalAndSwallowed() throws Exception {
        when(paymentService.processPaymentRequested(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenThrow(new PaymentGatewayException("card declined"));

        assertThatCode(() -> consumer.onPaymentRequested(
                envelope("payment_requested", goodPayload()))).doesNotThrowAnyException();
    }

    @Test
    void concurrentDuplicateClaim_isSwallowed() throws Exception {
        when(paymentService.processPaymentRequested(anyLong(), anyLong(), any(), anyString(), anyString()))
                .thenThrow(new DataIntegrityViolationException("claim lost"));

        assertThatCode(() -> consumer.onPaymentRequested(
                envelope("payment_requested", goodPayload()))).doesNotThrowAnyException();
    }

    @Test
    void missingIdempotencyKey_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(envelope("payment_requested",
                """
                {"orderId":10,"customerId":20,"amount":"5","currency":"INR"}""")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void blankIdempotencyKey_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(envelope("payment_requested",
                """
                {"orderId":10,"customerId":20,"amount":"5","currency":"INR","idempotencyKey":"   "}""")))
                .isInstanceOf(PoisonEventException.class);
    }

    @Test
    void zeroOrderId_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(envelope("payment_requested",
                """
                {"orderId":0,"customerId":20,"amount":"5","currency":"INR","idempotencyKey":"k"}""")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void negativeCustomerId_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(envelope("payment_requested",
                """
                {"orderId":10,"customerId":-1,"amount":"5","currency":"INR","idempotencyKey":"k"}""")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("customerId");
    }

    @Test
    void missingAmount_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(envelope("payment_requested",
                """
                {"orderId":10,"customerId":20,"currency":"INR","idempotencyKey":"k"}""")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void nonNumericAmount_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(envelope("payment_requested",
                """
                {"orderId":10,"customerId":20,"amount":"ten","currency":"INR","idempotencyKey":"k"}""")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("non-numeric");
    }

    @Test
    void malformedEnvelope_isPoison() {
        assertThatThrownBy(() -> consumer.onPaymentRequested("not-json"))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("Malformed event envelope");
    }

    @Test
    void malformedPayload_isPoison() throws Exception {
        assertThatThrownBy(() -> consumer.onPaymentRequested(
                envelope("payment_requested", "{ broken")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("Malformed event payload");
    }

    private Payment settledPayment() {
        Payment payment = new Payment();
        payment.setId(99L);
        payment.setProviderRef("pay_x");
        return payment;
    }
}
