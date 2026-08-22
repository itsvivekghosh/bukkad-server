package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.BlankResponse;
import com.bhukkad.idempotency.WebhookIdempotencyService;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PaymentWebhookController controller =
            new PaymentWebhookController(paymentGateway, paymentService, webhookIdempotencyService, objectMapper);

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
}
