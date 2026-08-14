package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.BlankResponse;
import com.bhukkad.payment.PaymentGateway;
import com.bhukkad.service.PaymentService;
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
            if ("payment.captured".equals(event)) {
                JsonNode payment = root.path("payload").path("payment").path("entity");
                String gatewayPaymentId = payment.path("id").asText();
                String gatewayOrderId = payment.path("order_id").asText();
                paymentService.completeWebhookPayment(gatewayOrderId, gatewayPaymentId);
            }
        } catch (Exception e) {
            log.error("Failed to process Razorpay webhook: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(ApiResponse.error("Webhook processing failed"));
        }

        return ResponseEntity.ok(ApiResponse.success("Webhook processed", null));
    }
}
