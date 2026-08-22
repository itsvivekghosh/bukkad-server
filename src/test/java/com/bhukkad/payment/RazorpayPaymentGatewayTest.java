package com.bhukkad.payment;

import com.bhukkad.payment.PaymentProperties;
import com.bhukkad.payment.RazorpayPaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RazorpayPaymentGateway#verifyWebhookSignature} — the
 * constant-time HMAC-SHA256 verification that protects the payment webhook.
 */
class RazorpayPaymentGatewayTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    private RazorpayPaymentGateway gateway;
    private PaymentProperties paymentProperties;

    @BeforeEach
    void setUp() {
        paymentProperties = new PaymentProperties();
        PaymentProperties.Razorpay razorpay = new PaymentProperties.Razorpay();
        razorpay.setWebhookSecret(WEBHOOK_SECRET);
        paymentProperties.setRazorpay(razorpay);

        gateway = new RazorpayPaymentGateway(paymentProperties, new ObjectMapper());
    }

    private String sign(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Test
    void validSignature_returnsTrue() throws Exception {
        String payload = "{\"event\":\"payment.captured\"}";
        assertTrue(gateway.verifyWebhookSignature(payload, sign(payload)));
    }

    @Test
    void validSignature_uppercaseHex_returnsTrue() throws Exception {
        String payload = "{\"event\":\"payment.captured\"}";
        assertTrue(gateway.verifyWebhookSignature(payload, sign(payload).toUpperCase()));
    }

    @Test
    void invalidSignature_returnsFalse() throws Exception {
        assertFalse(gateway.verifyWebhookSignature("{\"event\":\"payment.captured\"}", "deadbeef"));
    }

    @Test
    void emptySignature_returnsFalse() {
        assertFalse(gateway.verifyWebhookSignature("payload", ""));
    }

    @Test
    void nullSignature_returnsFalse() {
        assertFalse(gateway.verifyWebhookSignature("payload", null));
    }

    @Test
    void tamperedPayload_returnsFalse() throws Exception {
        String original = "{\"event\":\"payment.captured\"}";
        String tampered = "{\"event\":\"payment.failed\"}";
        assertFalse(gateway.verifyWebhookSignature(tampered, sign(original)));
    }

    @Test
    void missingWebhookSecret_returnsFalse() {
        ReflectionTestUtils.setField(paymentProperties.getRazorpay(), "webhookSecret", "");
        assertFalse(gateway.verifyWebhookSignature("payload", "deadbeef"));
    }
}
