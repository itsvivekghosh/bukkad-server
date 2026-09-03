package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.BlankResponse;
import com.bhukkad.idempotency.WebhookIdempotencyService;
import com.bhukkad.logging.alert.AlertService;
import com.bhukkad.common.outbox.OutboxEventService;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.ratelimit.RateLimitDecision;
import com.bhukkad.ratelimit.RateLimitService;
import com.bhukkad.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PaymentWebhookController} replay protection: a
 * duplicate {@code payment.captured} event must be acknowledged without
 * re-applying payment completion.
 */
class PaymentWebhookControllerTest {

    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final WebhookIdempotencyService webhookIdempotencyService = mock(WebhookIdempotencyService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final OutboxEventService outboxEventService = mock(OutboxEventService.class);
    private final AlertService alertService = mock(AlertService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentWebhookController controller =
            new PaymentWebhookController(paymentGateway, paymentService, webhookIdempotencyService,
                    rateLimitService, outboxEventService, alertService, objectMapper);

    @org.junit.jupiter.api.BeforeEach
    void stubRateLimitAllowed() {
        when(rateLimitService.check(anyString(), anyString()))
                .thenReturn(RateLimitDecision.allowed(1, 120, 60));
    }

    private String capturedPayload(String eventId, String gatewayOrderId, String gatewayPaymentId) {
        return """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "id": "%s",
                        "order_id": "%s"
                      }
                    }
                  }
                }
                """.formatted(eventId, gatewayOrderId, gatewayPaymentId);
    }

    @Test
    void firstDelivery_processesPayment() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        // The payment entity id doubles as the dedup key for Razorpay webhooks.
        when(webhookIdempotencyService.markProcessed("pay_1")).thenReturn(true);

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(
                        capturedPayload("pay_1", "order_1", "pay_1"), "sig");

        assertEquals(200, response.getStatusCode().value());
        verify(paymentService).completeWebhookPayment("order_1", "pay_1");
    }

    @Test
    void duplicateDelivery_skipsPaymentProcessing() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        // Duplicate insert propagates DataIntegrityViolationException from the
        // REQUIRES_NEW transaction; the controller acknowledges the duplicate.
        when(webhookIdempotencyService.markProcessed("pay_1"))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(
                        capturedPayload("pay_1", "order_1", "pay_1"), "sig");

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Webhook duplicate ignored", response.getBody().getMessage());
        verify(paymentService, never()).completeWebhookPayment(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void invalidSignature_rejected() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(capturedPayload("pay_evt_2", "order_1", "pay_2"), "bad-sig");

        assertEquals(400, response.getStatusCode().value());
        verify(paymentService, never()).completeWebhookPayment(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nonCapturedEvent_isIgnored() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        String payload = "{\"event\": \"payment.failed\", \"payload\": {\"payment\": {\"entity\": {\"id\": \"pay_3\"}}}}";

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(payload, "sig");

        assertEquals(200, response.getStatusCode().value());
        verify(paymentService, never()).completeWebhookPayment(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    // ==================== additional coverage ====================

    @Test
    void missingSignature_rejected() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(false);

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(capturedPayload("pay_9", "order_1", "pay_9"), null);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void invalidJsonPayload_returnsBadRequest() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook("{not-json", "sig");

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Invalid webhook payload", response.getBody().getMessage());
    }

    @Test
    void simplifiedTestFormat_withoutEntityNode_completesFromRootFields() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(webhookIdempotencyService.markProcessed("pay_root")).thenReturn(true);
        String payload = """
                {
                  "event": "payment.captured",
                  "orderId": "order_root",
                  "paymentId": "pay_root"
                }
                """;

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(payload, "sig");

        assertEquals(200, response.getStatusCode().value());
        verify(paymentService).completeWebhookPayment("order_root", "pay_root");
    }

    @Test
    void missingGatewayIds_returnsBadRequest() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        String payload = "{\"event\": \"payment.captured\"}";

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(payload, "sig");

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Missing gateway order or payment id", response.getBody().getMessage());
    }

    @Test
    void paymentCompletionFailure_returnsInternalServerError() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(webhookIdempotencyService.markProcessed("pay_err")).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("db exploded"))
                .when(paymentService).completeWebhookPayment("order_e", "pay_err");

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(capturedPayload("pay_err", "order_e", "pay_err"), "sig");

        assertEquals(500, response.getStatusCode().value());
    }

    @Test
    void unknownOrder_returnsNotFound() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(webhookIdempotencyService.markProcessed("pay_nf")).thenReturn(true);
        org.mockito.Mockito.doThrow(new com.bhukkad.common.error.ResourceNotFoundException("Payment not found"))
                .when(paymentService).completeWebhookPayment("order_nf", "pay_nf");

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(capturedPayload("pay_nf", "order_nf", "pay_nf"), "sig");

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void illegalArgument_returnsBadRequest() {
        when(paymentGateway.verifyWebhookSignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(webhookIdempotencyService.markProcessed("pay_bad")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad request"))
                .when(paymentService).completeWebhookPayment("order_b", "pay_bad");

        ResponseEntity<ApiResponse<BlankResponse>> response =
                controller.handleRazorpayWebhook(capturedPayload("pay_bad", "order_b", "pay_bad"), "sig");

        assertEquals(400, response.getStatusCode().value());
    }
}
