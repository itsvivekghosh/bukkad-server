package com.bhukkad.payment.api;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.service.WebhookService;
import com.bhukkad.payment.infrastructure.client.RazorpayWebhookVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentWebhookControllerTest {

    @Mock private RazorpayWebhookVerifier signatureVerifier;
    @Mock private WebhookService webhookService;

    private PaymentWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PaymentWebhookController(
                signatureVerifier, webhookService, new ObjectMapper());
    }

    private static String capturedPayload() {
        return """
                {"event":"payment.captured",
                 "payload":{"payment":{"entity":{"id":"pay_123","order_id":"order_45"}}}}""";
    }

    @Test
    void invalidSignature_rejectedWith400() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(false);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "bad-sig", "evt-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Invalid signature");
        verify(webhookService, never()).completeFromWebhook(any(), any(), any(), any());
    }

    @Test
    void capturedEvent_settlesWithEntityIds() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        when(webhookService.isKnownEvent("evt-1")).thenReturn(false);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "sig", "evt-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Webhook processed");
        verify(webhookService).completeFromWebhook("order_45", "pay_123", "evt-1", "SETTLED");
    }

    @Test
    void refundedEvent_mapsToRefunded() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        String payload = """
                {"event":"payment.refunded",
                 "payload":{"payment":{"entity":{"id":"pay_9","order_id":"order_9"}}}}""";

        ResponseEntity<String> response = controller.handleRazorpayWebhook(payload, "sig", "evt-2");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookService).completeFromWebhook("order_9", "pay_9", "evt-2", "REFUNDED");
    }

    @Test
    void unrelatedEvent_ignored() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                """
                {"event":"payment.created","payload":{"payment":{"entity":{"id":"pay_1"}}}}""",
                "sig", "evt-3");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Webhook event ignored");
        verify(webhookService, never()).completeFromWebhook(any(), any(), any(), any());
    }

    @Test
    void missingEntityNode_fallsBackToRootIds() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                """
                {"event":"payment.captured","paymentId":"pay_77","orderId":"order_77"}""",
                "sig", "evt-4");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookService).completeFromWebhook("order_77", "pay_77", "evt-4", "SETTLED");
    }

    @Test
    void missingGatewayIdentifiers_badRequest() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                """
                {"event":"payment.captured","payload":{"payment":{"entity":{}}}}""",
                "sig", "evt-5");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Missing gateway order or payment id");
    }

    @Test
    void knownEvent_fastPathDuplicate() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        when(webhookService.isKnownEvent("evt-6")).thenReturn(true);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "sig", "evt-6");

        assertThat(response.getBody()).isEqualTo("Webhook duplicate ignored");
        verify(webhookService, never()).completeFromWebhook(any(), any(), any(), any());
    }

    @Test
    void concurrentClaimRace_answeredBenignDuplicate() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        doThrow(new DataIntegrityViolationException("dup"))
                .when(webhookService).completeFromWebhook(any(), any(), any(), any());

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "sig", "evt-7");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("Webhook duplicate ignored");
    }

    @Test
    void malformedJson_badRequest() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                "{ not json", "sig", "evt-8");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("Invalid webhook payload");
    }

    @Test
    void resourceNotFound_maps404() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        doThrow(new ResourceNotFoundException("no payment for order"))
                .when(webhookService).completeFromWebhook(any(), any(), any(), any());

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "sig", "evt-9");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo("no payment for order");
    }

    @Test
    void illegalArgument_maps400() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        doThrow(new IllegalArgumentException("illegal transition"))
                .when(webhookService).completeFromWebhook(any(), any(), any(), any());

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "sig", "evt-10");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("illegal transition");
    }

    @Test
    void unexpectedFailure_maps500() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        doThrow(new IllegalStateException("outbox down"))
                .when(webhookService).completeFromWebhook(any(), any(), any(), any());

        ResponseEntity<String> response = controller.handleRazorpayWebhook(
                capturedPayload(), "sig", "evt-11");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo("Webhook processing failed");
    }

    @Test
    void absentEventIdHeader_fallsBackToSha256OfPayload() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);
        String payload = capturedPayload();

        controller.handleRazorpayWebhook(payload, "sig", null);

        String expected = sha256Hex(payload);
        verify(webhookService).completeFromWebhook(eq("order_45"), eq("pay_123"), eq(expected), eq("SETTLED"));
    }

    @Test
    void eventHeader_isTrimmed() {
        when(signatureVerifier.verifyWebhookSignature(anyString(), any())).thenReturn(true);

        controller.handleRazorpayWebhook(capturedPayload(), "sig", "  evt-space  ");

        verify(webhookService).completeFromWebhook("order_45", "pay_123", "evt-space", "SETTLED");
    }

    private static String sha256Hex(String payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
