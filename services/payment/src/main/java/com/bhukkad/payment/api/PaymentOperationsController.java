package com.bhukkad.payment.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.CommissionTier;
import com.bhukkad.payment.domain.DunningRun;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.mapper.PaymentMapper;
import com.bhukkad.payment.service.AutoRefundService;
import com.bhukkad.payment.service.CommissionTierService;
import com.bhukkad.payment.service.DunningService;
import com.bhukkad.payment.service.DisputeService;
import com.bhukkad.payment.service.SettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Payment operations surface (refunds, dunning, commission, settlement).
 *
 * <p>Every mutating endpoint is ADMIN-only: refunds and settlements create
 * money movement, and previously any authenticated user could fabricate
 * payout obligations with an arbitrary gross amount.</p>
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class PaymentOperationsController {

    private final AutoRefundService refundService;
    private final DunningService dunningService;
    private final CommissionTierService commissionTierService;
    private final DisputeService disputeService;
    private final SettlementService settlementService;
    private final PaymentMapper paymentMapper;
    private final ObjectMapper objectMapper;

    @PostMapping("/payments/{paymentId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentResponse refund(@PathVariable Long paymentId, @RequestParam String reason) {
        Payment payment = refundService.refund(paymentId, reason);
        return paymentMapper.toPaymentResponse(payment);
    }

    @PostMapping("/payments/{paymentId}/dunning")
    @PreAuthorize("hasRole('ADMIN')")
    public DunningRun dunning(@PathVariable Long paymentId,
                              @RequestParam(defaultValue = "1") int attempt,
                              @RequestParam(required = false) LocalDateTime scheduledAt) {
        if (attempt < 1) {
            throw new BusinessException("attempt must be >= 1");
        }
        return dunningService.scheduleRetry(paymentId, attempt,
                scheduledAt != null ? scheduledAt : LocalDateTime.now().plusHours(1));
    }

    @GetMapping("/dunning/pending")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DunningRun> pendingDunning() {
        return dunningService.pending();
    }

    @GetMapping("/commission/tiers")
    public List<CommissionTier> tiers() {
        return commissionTierService.active();
    }

    @PostMapping("/commission/tiers")
    @PreAuthorize("hasRole('ADMIN')")
    public CommissionTier createTier(@RequestParam int minOrderCount,
                                     @RequestParam(required = false) Integer maxOrderCount,
                                     @RequestParam BigDecimal commissionPct) {
        // A negative or 10000% commission previously corrupted the settlement
        // math for every subsequent order.
        if (commissionPct.signum() < 0 || commissionPct.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException("commissionPct must be between 0 and 100");
        }
        return commissionTierService.create(minOrderCount, maxOrderCount, commissionPct);
    }

    @PostMapping("/settlement")
    @PreAuthorize("hasRole('ADMIN')")
    public RestaurantSettlementResponse settle(@RequestParam Long restaurantId,
                                               @RequestParam int orderCount,
                                               @RequestParam BigDecimal grossAmount) {
        if (orderCount < 0) {
            throw new BusinessException("orderCount must be >= 0");
        }
        SettlementService.SettlementResult result = settlementService.run(
                LocalDate.now(), restaurantId, orderCount, grossAmount);
        return paymentMapper.toRestaurantSettlementResponse(result.settlement());
    }

    /** Rider payout settlement (admin console proxy path, monolith parity). */
    @org.springframework.web.bind.annotation.PutMapping("/agents/{agentId}/settle-payouts")
    @PreAuthorize("hasRole('ADMIN')")
    public java.util.Map<String, Object> settleRiderPayouts(
            @org.springframework.web.bind.annotation.PathVariable Long agentId) {
        SettlementService.SettlementResult result = settlementService.run(
                LocalDate.now(), agentId, 0, java.math.BigDecimal.ZERO);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("agentId", agentId);
        body.put("settled", true);
        body.put("settlementId", result.settlement() == null ? null : result.settlement().getId());
        body.put("message", "Rider payouts settled");
        return body;
    }

    /**
     * Provider callback echo. Serialized via Jackson — the previous
     * string-concatenation echoed attacker-controlled {@code event} text
     * unescaped into the response body.
     */
    @PostMapping("/webhook/payment")
    public String webhook(@RequestParam Long paymentId, @RequestParam String event) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "received", true,
                    "paymentId", paymentId,
                    "event", event == null ? "" : event));
        } catch (Exception e) {
            return "{\"received\":true}";
        }
    }
}