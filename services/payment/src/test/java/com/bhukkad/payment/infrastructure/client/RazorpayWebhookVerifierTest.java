package com.bhukkad.payment.infrastructure.client;

import com.bhukkad.payment.PaymentProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookVerifierTest {

    private static RazorpayWebhookVerifier withSecret(String secret) {
        PaymentProperties properties = new PaymentProperties();
        properties.getRazorpay().setWebhookSecret(secret);
        return new RazorpayWebhookVerifier(properties);
    }

    private static String hmac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void validSignatureAccepted() {
        String payload = "{\"event\":\"payment.captured\"}";
        RazorpayWebhookVerifier verifier = withSecret("s3cr3t");

        assertThat(verifier.verifyWebhookSignature(payload, hmac("s3cr3t", payload))).isTrue();
    }

    @Test
    void signatureComparisonIsCaseInsensitive() {
        String payload = "raw-body";
        RazorpayWebhookVerifier verifier = withSecret("k");

        assertThat(verifier.verifyWebhookSignature(payload, hmac("k", payload).toUpperCase()))
                .isTrue();
    }

    @Test
    void paddedSignatureIsNotTrimmedAndFails() {
        RazorpayWebhookVerifier verifier = withSecret("k");

        assertThat(verifier.verifyWebhookSignature("raw-body", " " + hmac("k", "raw-body")))
                .isFalse();
    }

    @Test
    void tamperedPayloadFailsVerification() {
        RazorpayWebhookVerifier verifier = withSecret("s3cr3t");
        String sig = hmac("s3cr3t", "{\"event\":\"payment.captured\"}");

        assertThat(verifier.verifyWebhookSignature("{\"event\":\"payment.refunded\"}", sig)).isFalse();
    }

    @Test
    void wrongSecretFailsVerification() {
        String payload = "body";
        RazorpayWebhookVerifier verifier = withSecret("other-secret");

        assertThat(verifier.verifyWebhookSignature(payload, hmac("s3cr3t", payload))).isFalse();
    }

    @Test
    void missingOrBlankSignatureRejectedWithoutHmac() {
        RazorpayWebhookVerifier verifier = withSecret("s3cr3t");

        assertThat(verifier.verifyWebhookSignature("body", null)).isFalse();
        assertThat(verifier.verifyWebhookSignature("body", "")).isFalse();
        assertThat(verifier.verifyWebhookSignature("body", "   ")).isFalse();
    }

    @Test
    void nonHexSignatureRejected() {
        RazorpayWebhookVerifier verifier = withSecret("s3cr3t");

        assertThat(verifier.verifyWebhookSignature("body", "not-hex-at-all")).isFalse();
    }
}
