package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.payment.AutoRefundService;
import com.bhukkad.payment.DunningService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PaymentOperationsController {

    private final AutoRefundService autoRefundService;
    private final DunningService dunningService;
    private final PaymentService paymentService;
    private final SecurityUtils securityUtils;

    /**
     * Admin override: triggers the cancellation-policy-based auto-refund flow for
     * an order. The refund is executed only when a matching policy exists and the
     * payment has not already been refunded.
     */
    @PostMapping(ApiPaths.V1_PREFIX + "/admin/payments/refund/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> triggerAutoRefund(
            @PathVariable Long orderId,
            @RequestBody(required = false) Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        boolean refunded = autoRefundService.autoRefund(orderId, reason);
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", orderId);
        data.put("reason", reason);
        data.put("refunded", refunded);
        return ResponseEntity.ok(ApiResponse.success(
                refunded ? "Auto refund processed" : "No refund applied", data));
    }

    /**
     * Customer-facing endpoint: returns the current payment status and retry
     * information for an order.
     */
    @GetMapping(ApiPaths.V1_PREFIX + "/payments/orders/{orderId}/payment-status")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPaymentStatus(
            @PathVariable Long orderId) {
        Map<String, Object> data = paymentService.getPaymentStatus(
                orderId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}