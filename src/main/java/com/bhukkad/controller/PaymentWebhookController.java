package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.BlankResponse;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.service.PaymentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/payments/webhooks")
@RequiredArgsConstructor
public class PaymentWebhookController {

    private final PaymentGateway paymentGateway;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @PostMapping("/razorpay")
    public ResponseEntity<ApiResponse<BlankResponse>> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {

        if (!paymentGateway.verifyWebhookSignature(payload, signature)) {
            log.warn("Rejected Razorpay webhook with invalid signature");
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid signature"));
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String event = root.path("event").asText();
            if (!"payment.captured".equals(event)) {
                return ResponseEntity.ok(ApiResponse.success("Webhook event ignored", null));
            }

            String gatewayPaymentId;
            String gatewayOrderId;

            JsonNode paymentNode = root.path("payload").path("payment").path("entity");
            if (paymentNode.isMissingNode() || paymentNode.isNull()) {
                // Simplified test payload format: orderId and paymentId at root level
                gatewayOrderId = root.path("orderId").asText();
                gatewayPaymentId = root.path("paymentId").asText();
            } else {
                gatewayPaymentId = paymentNode.path("id").asText();
                gatewayOrderId = paymentNode.path("order_id").asText();
            }

            if (!hasText(gatewayOrderId) || !hasText(gatewayPaymentId)) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Missing gateway order or payment id"));
            }

            paymentService.completeWebhookPayment(gatewayOrderId, gatewayPaymentId);
        } catch (JsonProcessingException e) {
            log.warn("Invalid JSON in Razorpay webhook payload: {}", e.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error("Invalid webhook payload"));
        } catch (ResourceNotFoundException ex) {
            log.warn("Webhook resource not found: {}", ex.getMessage());
            return ResponseEntity.status(404).body(ApiResponse.error(ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Webhook bad request: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
        } catch (Exception e) {
            log.error("Failed to process Razorpay webhook: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(ApiResponse.error("Webhook processing failed"));
        }

        return ResponseEntity.ok(ApiResponse.success("Webhook processed", null));
    }

    private static boolean hasText(String str) {
        return str != null && !str.isBlank();
    }
}
