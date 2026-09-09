package com.bhukkad.payment.service;

import com.bhukkad.common.outbox.OutboxEventService;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.idempotency.WebhookIdempotencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V-11/PERF-2 unit contract: claim → settle → enqueue, all delegated in that
 * order inside one transaction; NO swallowing anywhere on the path. The SQL
 * atomicity itself is verified by WebhookAtomicityPostgresIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private PaymentService paymentService;
    @Mock
    private OutboxEventService outboxEventService;
    @Mock
    private WebhookIdempotencyService webhookIdempotencyService;

    @InjectMocks
    private WebhookService webhookService;

    @Test
    void completesInOrder_claimSettleEnqueue() {
        Payment payment = new Payment();
        payment.setId(5L);
        payment.setOrderId(9L);
        when(paymentService.completeWebhookPayment("order-1", "pay-1")).thenReturn(payment);

        WebhookService.Outcome outcome =
                webhookService.completeFromWebhook("order-1", "pay-1", "evt-123");

        assertThat(outcome).isEqualTo(WebhookService.Outcome.PROCESSED);
        InOrder order = inOrder(webhookIdempotencyService, paymentService, outboxEventService);
        order.verify(webhookIdempotencyService).claim("evt-123");
        order.verify(paymentService).completeWebhookPayment("order-1", "pay-1");
        order.verify(outboxEventService)
                .enqueue(eq("PAYMENT_WEBHOOK_RECEIVED"), eq(9L), anyMap());
    }

    @Test
    void enqueueFailure_propagatesSoTheTxRollsBackTheSettlement() {
        Payment payment = new Payment();
        payment.setId(5L);
        payment.setOrderId(9L);
        when(paymentService.completeWebhookPayment(anyString(), anyString())).thenReturn(payment);
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxEventService).enqueue(anyString(), anyLong(), any());

        // No catch here: G-1 — the whole business tx must roll back.
        assertThatThrownBy(() -> webhookService.completeFromWebhook("order-1", "pay-1", "evt-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("outbox down");
    }

    @Test
    void concurrentDuplicateClaim_violationPropagates() {
        org.springframework.dao.DataIntegrityViolationException dup =
                new org.springframework.dao.DataIntegrityViolationException("unique (scope,key)");
        doThrow(dup).when(webhookIdempotencyService).claim("evt-race");

        // The loser of the race must NOT settle/enqueue; the controller maps
        // this to a benign duplicate 200 with the tx already rolled back.
        assertThatThrownBy(() -> webhookService.completeFromWebhook("order-1", "pay-1", "evt-race"))
                .isSameAs(dup);
        verify(paymentService, org.mockito.Mockito.never())
                .completeWebhookPayment(anyString(), anyString());
    }
}
