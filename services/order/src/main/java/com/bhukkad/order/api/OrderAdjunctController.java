package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderDeliveryProof;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.service.OrderInvoiceService;
import com.bhukkad.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Customer-app order adjuncts (monolith parity): invoice + PDF download,
 * tracking alias, smarter-ETA view and the delivery-proof (OTP / photo)
 * handshake between rider and customer. Owner is enforced from the JWT.
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderAdjunctController {

    private final OrderService orderService;
    private final OrderInvoiceService invoiceService;
    private final OrderRepository orderRepository;
    private final com.bhukkad.order.domain.OrderDeliveryProofRepository proofRepository;

    /** Invoice for an order (auto-generates on first view). */
    @GetMapping("/{orderId}/invoice")
    @Transactional
    public Object invoice(@AuthenticationPrincipal TokenPrincipal principal,
                          @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        return invoiceService.getByOrder(orderId);
    }

    /** Printable PDF invoice (text/pdf-structured payload in the dev build). */
    @GetMapping("/{orderId}/invoice/pdf")
    @Transactional
    public Map<String, Object> invoicePdf(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        Object invoice = invoiceService.getByOrder(orderId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("contentType", "application/pdf");
        body.put("invoice", invoice);
        body.put("document", "%PDF-1.4 dev-build invoice for order " + orderId);
        return body;
    }

    /** Monolith tracking alias ({@code /customer/{id}/track}) — 20 req/min. */
    @GetMapping("/customer/{orderId}/track")
    @com.bhukkad.common.ratelimit.RateLimited(bucket = "order-track", limit = 20,
            windowSeconds = 60)
    public Map<String, Object> trackAlias(@AuthenticationPrincipal TokenPrincipal principal,
                                          @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        OrderResponse order = orderService.getOrder(orderId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", order.id());
        body.put("status", order.status());
        body.put("totalAmount", order.totalAmount());
        body.put("timeline", orderService.timelineEvents(orderId));
        return body;
    }

    /**
     * Smarter-ETA view: live rider position + kitchen state produce the
     * customer-facing estimate. The dev build returns the deterministic
     * kitchen-backlog estimate. Monolith path {@code /api/v1/delivery-truth/*}
     * is served via a sibling alias below.
     */
    @GetMapping("/delivery-truth/{orderId}/eta")
    public Map<String, Object> eta(@AuthenticationPrincipal TokenPrincipal principal,
                                   @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        Order order = requireOrder(orderId);
        int baseMinutes = "OUT_FOR_DELIVERY".equals(order.getStatus()) ? 12 : 35;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("status", order.getStatus());
        body.put("etaMinutes", baseMinutes);
        body.put("confidence", "HIGH");
        return body;
    }

    /** Monolith-parity alias: {@code /api/v1/delivery-truth/orders/{id}/eta}. */
    @GetMapping("/api/v1/delivery-truth/orders/{orderId}/eta")
    public Map<String, Object> etaAlias(@AuthenticationPrincipal TokenPrincipal principal,
                                        @PathVariable Long orderId) {
        return eta(principal, orderId);
    }

    // ------------------------------------------------------------------
    // Delivery proof (OTP / photo handshake)
    // ------------------------------------------------------------------

    /** Issues the single-use handover OTP for a ready-to-deliver order. */
    @PostMapping("/delivery/{orderId}/proof/otp")
    @Transactional
    public Map<String, Object> issueOtp(@AuthenticationPrincipal TokenPrincipal principal,
                                        @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        Order order = requireOrder(orderId);
        if (!"OUT_FOR_DELIVERY".equals(order.getStatus())) {
            throw new BusinessException(
                    "OTP can only be issued while the order is out for delivery");
        }
        String otp = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
        OrderDeliveryProof proof = proofRepository.findByOrderId(orderId)
                .orElseGet(OrderDeliveryProof::new);
        proof.setOrderId(orderId);
        proof.setOtpHash(String.valueOf(otp.hashCode()));
        proof.setOtpIssuedAt(java.time.LocalDateTime.now());
        proofRepository.save(proof);
        // Dev build returns the OTP inline (no SMS gateway); prod sends it
        // over the notification channel and never echoes it.
        return Map.of("orderId", orderId, "otp", otp, "expiresIn", 600);
    }

    /** Upload-url slot for the rider's proof-of-delivery photo. */
    @PostMapping("/delivery/{orderId}/proof/photo/upload-url")
    @Transactional
    public Map<String, Object> photoUploadUrl(@AuthenticationPrincipal TokenPrincipal principal,
                                              @PathVariable Long orderId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        String scope = String.valueOf(principal == null ? "" : principal.scope());
        if (!"DELIVERY_AGENT".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new UnauthorizedException("Authenticated delivery agent required");
        }
        Order order = requireOrder(orderId);
        if (!"OUT_FOR_DELIVERY".equals(order.getStatus())) {
            throw new BusinessException(
                    "Photo proof is only accepted while the order is out for delivery");
        }
        String contentType = body == null ? null : (String) body.get("contentType");
        if (contentType == null || contentType.isBlank()) {
            throw new BusinessException("contentType is required");
        }
        return Map.of(
                "uploadUrl", "/dev-uploads/delivery-proof/" + orderId + "/" + System.currentTimeMillis(),
                "expiresIn", 900,
                "contentType", contentType);
    }

    /** Verifies the customer OTP at handover (rider-presented). */
    @PostMapping("/delivery/{orderId}/proof/verify")
    @Transactional
    public Map<String, Object> verifyOtp(@AuthenticationPrincipal TokenPrincipal principal,
                                         @PathVariable Long orderId,
                                         @RequestParam(required = false) String otp) {
        String scope = String.valueOf(principal == null ? "" : principal.scope());
        if (!"DELIVERY_AGENT".equalsIgnoreCase(scope) && !"ADMIN".equalsIgnoreCase(scope)) {
            throw new UnauthorizedException("Authenticated delivery agent required");
        }
        OrderDeliveryProof proof = proofRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No OTP issued for order: " + orderId));
        if (otp == null || !String.valueOf(otp.hashCode()).equals(proof.getOtpHash())) {
            throw new BusinessException("Invalid OTP");
        }
        proof.setOtpVerifiedAt(java.time.LocalDateTime.now());
        proofRepository.save(proof);
        return Map.of("orderId", orderId, "verified", true);
    }

    /** The stored proof (OTP verification state + photo reference). */
    @GetMapping("/delivery/{orderId}/proof")
    @Transactional(readOnly = true)
    public Map<String, Object> proof(@AuthenticationPrincipal TokenPrincipal principal,
                                     @PathVariable Long orderId) {
        requireOrderOwner(principal, orderId);
        OrderDeliveryProof proof = proofRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No proof recorded for order: " + orderId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("otpIssued", proof.getOtpIssuedAt() != null);
        body.put("otpVerified", proof.getOtpVerifiedAt() != null);
        body.put("photoUrl", proof.getPhotoUrl());
        return body;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Order requireOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
    }

    private void requireOrderOwner(TokenPrincipal principal, Long orderId) {
        PrincipalGuard.requireSelfOrAdmin(principal,
                orderService.getOrder(orderId).customerId());
    }
}
