package com.bhukkad.payment.domain.service;

import com.bhukkad.common.outbox.OutboxEventService;
import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.infrastructure.persistence.WebhookIdempotencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookServiceClaimUnitTest {

    @Mock private PaymentService paymentService;
    @Mock private OutboxEventService outboxEventService;
    @Mock private WebhookIdempotencyService webhookIdempotencyService;

    private WebhookService service() {
        return new WebhookService(paymentService, outboxEventService, webhookIdempotencyService);
    }

    @Test
    void completeFromWebhook_claimsSettlesAndEnqueuesAtomically() {
        Payment payment = new Payment();
        payment.setId(12L);
        payment.setOrderId(34L);
        payment.setStatus("SETTLED");
        when(paymentService.completeWebhookPayment("order_x", "pay_x", "SETTLED"))
                .thenReturn(payment);

        WebhookService.Outcome outcome =
                service().completeFromWebhook("order_x", "pay_x", "evt-1", "SETTLED");

        assertThat(outcome).isEqualTo(WebhookService.Outcome.PROCESSED);
        verify(webhookIdempotencyService).claim("evt-1");
        verify(outboxEventService).enqueue(eq("PAYMENT_WEBHOOK_RECEIVED"), eq(34L),
                argThatMap(map -> map.get("eventId").equals("evt-1")
                        && map.get("paymentId").equals("12")
                        && map.get("status").equals("SETTLED")));
    }

    @Test
    void completeFromWebhook_nullEventId_enqueuesEmptyDedupToken() {
        Payment payment = new Payment();
        payment.setId(1L);
        payment.setOrderId(2L);
        payment.setStatus("REFUNDED");
        when(paymentService.completeWebhookPayment("o", "p", "REFUNDED")).thenReturn(payment);

        service().completeFromWebhook("o", "p", null, "REFUNDED");

        verify(outboxEventService).enqueue(eq("PAYMENT_WEBHOOK_RECEIVED"), eq(2L),
                argThatMap(map -> map.get("eventId").equals("")));
    }

    @Test
    void isKnownEvent_delegatesToIdempotencyProbe() {
        when(webhookIdempotencyService.isAlreadyProcessed("evt-9")).thenReturn(true);

        assertThat(service().isKnownEvent("evt-9")).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argThatMap(
            java.util.function.Predicate<Map<String, Object>> predicate) {
        return org.mockito.ArgumentMatchers.argThat((Map<String, Object> map) -> predicate.test(map));
    }
}
