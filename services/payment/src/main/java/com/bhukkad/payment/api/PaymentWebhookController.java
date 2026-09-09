package com.bhukkad.payment.api;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.outbox.OutboxEventService;
import com.bhukkad.common.web.RequestUtils;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.gateway.RazorpayWebhookVerifier;
import com.bhukkad.payment.idempotency.WebhookIdempotencyService;
import com.bhukkad.payment.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Razorpay webhook intake (ported from the monolith's PaymentWebhookController).
 *
 * <p>Flow: rate limit by client IP → verify HmacSHA256 signature → dedupe by
 * provider event id → mark the payment captured → enqueue
 * {@code PAYMENT_WEBHOOK_RECEIVED} to the payment service's outbox. External
 * callers hit this through the gateway; no customer auth applies (providers
 * authenticate via the signature).</p>
 */
@RestController
@RequestMapping("/api/v1/payments/webhooks")
public class PaymentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookController.class);

    private final RazorpayWebhookVerifier signatureVerifier;
    private final WebhookIdempotencyService webhookIdempotencyService;
    private final PaymentService paymentService;
    private final OutboxEventService outboxEventService;
    private final ObjectMapper objectMapper;

    public PaymentWebhookController(RazorpayWebhookVerifier signatureVerifier,
                                    WebhookIdempotencyService webhookIdempotencyService,
                                    PaymentService paymentService,
                                    OutboxEventService outboxEventService,
                                    ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookIdempotencyService = webhookIdempotencyService;
        this.paymentService = paymentService;
        this.outboxEventService = outboxEventService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/razorpay")
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

            // Fast-path dedup: skip re-completion for known events. The insert
            // below is still the race-safe guarantee — this check only avoids
            // repeating work on ordinary provider redeliveries.
            String eventId = eventId(root);
            if (webhookIdempotencyService.isAlreadyProcessed(eventId)) {
                log.info("Webhook duplicate ignored | eventId={}", eventId);
                return ResponseEntity.ok("Webhook duplicate ignored");
            }

            // Apply side effects FIRST, then record the event id. Marking before
            // completing would burn the event id on a transient failure (e.g.
            // unknown order) and the provider's retry could never complete it.
            // Concurrent duplicate delivery loses the insert race below and is
            // acknowledged as a duplicate instead of re-applying the effect
            // (completion itself is idempotent: SETTLED -> SETTLED).
            Payment payment = paymentService.completeWebhookPayment(gatewayOrderId, gatewayPaymentId);

            try {
                webhookIdempotencyService.markProcessed(eventId);
            } catch (org.springframework.dao.DataIntegrityViolationException ex) {
                log.info("Webhook duplicate delivery acknowledged after completion | eventId={}",
                        eventId);
            }

            try {
                outboxEventService.enqueue("PAYMENT_WEBHOOK_RECEIVED", payment.getOrderId(),
                        Map.of("eventId", eventId,
                                "gatewayOrderId", gatewayOrderId,
                                "gatewayPaymentId", gatewayPaymentId,
                                "paymentId", String.valueOf(payment.getId())));
            } catch (Exception enqueueEx) {
                log.warn("Failed to enqueue webhook outbox event | eventId={} | error={}",
                        eventId, enqueueEx.getMessage());
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