package com.bhukkad.payment.api;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.ratelimit.RateLimited;
import com.bhukkad.common.web.RequestUtils;
import com.bhukkad.payment.gateway.RazorpayWebhookVerifier;
import com.bhukkad.payment.service.WebhookService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Razorpay webhook intake (ported from the monolith's PaymentWebhookController).
 *
 * <p>Flow (PERF-2/V-11): rate-limit by client IP/bucket → verify HmacSHA256
 * signature → cheap duplicate fast-path on the provider event id → delegate
 * the transactional unit (dedup claim + settlement + outbox enqueue, ALL IN
 * ONE TRANSACTION) to {@link WebhookService}. Losing the concurrent-duplicate
 * race (unique {@code (scope, key)} violation) is answered with a benign
 * 200 — the winner's delivery applied the effects. Any failure inside the
 * transaction (including the outbox enqueue) rolls settlement back so the
 * provider's retry can redo the whole unit: the money trail can no longer
 * diverge from the event trail (RC-D / G-1).</p>
 */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final RazorpayWebhookVerifier signatureVerifier;
    private final WebhookService webhookService;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(RazorpayWebhookVerifier signatureVerifier,
                                    WebhookService webhookService,
                                    ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookService = webhookService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay")
    @RateLimited(bucket = "razorpay-webhook", limit = 600, windowSeconds = 60)
    public ResponseEntity<String> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        if (!signatureVerifier.verifyWebhookSignature(payload, signature)) {
            log.warn("Rejected Razorpay webhook with invalid signature | ip={}",
                    RequestUtils.resolveClientIp());
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String event = root.path("event").asText();
            if (!"payment.captured".equals(event)) {
                return ResponseEntity.ok("Webhook event ignored");
            }

            JsonNode paymentNode = root.path("payload").path("payment").path("entity");
            String gatewayPaymentId = paymentNode.isMissingNode() || paymentNode.isNull()
                    ? root.path("paymentId").asText()
                    : paymentNode.path("id").asText();
            String gatewayOrderId = paymentNode.isMissingNode() || paymentNode.isNull()
                    ? root.path("orderId").asText()
                    : paymentNode.path("order_id").asText();

            if (!hasText(gatewayOrderId) || !hasText(gatewayPaymentId)) {
                return ResponseEntity.badRequest().body("Missing gateway order or payment id");
            }

            String eventId = eventId(root);
            // Fast-path dedup for ordinary provider redeliveries; the in-tx
            // unique claim inside WebhookService remains the race-safe guard.
            if (webhookService.isKnownEvent(eventId)) {
                log.info("Webhook duplicate ignored | eventId={}", eventId);
                return ResponseEntity.ok("Webhook duplicate ignored");
            }

            try {
                webhookService.completeFromWebhook(gatewayOrderId, gatewayPaymentId, eventId);
            } catch (DataIntegrityViolationException dup) {
                // Concurrent delivery of the same event id won the claim; the
                // whole unit rolled back here. The effects are applied ONCE.
                log.info("Webhook duplicate delivery (concurrent claim) | eventId={}", eventId);
                return ResponseEntity.ok("Webhook duplicate ignored");
            }
        } catch (JsonProcessingException e) {
            log.warn("Invalid JSON in Razorpay webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Invalid webhook payload");
        } catch (ResourceNotFoundException ex) {
            log.warn("Webhook resource not found: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.warn("Webhook bad request: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(ex.getMessage());
        } catch (Exception e) {
            // Includes outbox enqueue failures: settlement rolled back with it
            // (G-1) and the provider redelivery re-processes the event.
            log.error("Failed to process Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body("Webhook processing failed");
        }

        return ResponseEntity.ok("Webhook processed");
    }

    private static String eventId(JsonNode root) {
        return root.path("payload").path("payment").path("entity").path("id").asText(
                root.path("paymentId").asText());
    }

    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }
}
