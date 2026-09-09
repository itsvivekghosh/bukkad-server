package com.bhukkad.payment.gateway;

import com.bhukkad.payment.PaymentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Razorpay webhook signature verification (ported from the monolith's
 * RazorpayPaymentGateway). HmacSHA256 over the raw payload, hex-encoded,
 * compared in constant time to avoid timing side channels.
 */
@Component
public class RazorpayWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookVerifier.class);

    private final PaymentProperties paymentProperties;

    public RazorpayWebhookVerifier(PaymentProperties paymentProperties) {
        this.paymentProperties = paymentProperties;
    }

    public boolean verifyWebhookSignature(String payload, String signature) {
        if (!StringUtils.hasText(signature)) {
            return false;
        }
        try {
            String secret = paymentProperties.getRazorpay().getWebhookSecret();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expected = bytesToHex(hash);
            return MessageDigest.isEqual(
                    expected.toLowerCase().getBytes(StandardCharsets.UTF_8),
                    signature.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Razorpay webhook signature verification failed: {}", e.getMessage());
            return false;
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}