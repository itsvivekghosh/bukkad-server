package com.bhukkad.payment.api;

import com.bhukkad.payment.domain.CommissionTier;
import com.bhukkad.payment.domain.DunningRun;
import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.mapper.PaymentMapper;
import com.bhukkad.payment.service.AutoRefundService;
import com.bhukkad.payment.service.CommissionTierService;
import com.bhukkad.payment.service.DunningService;
import com.bhukkad.payment.service.DisputeService;
import com.bhukkad.payment.service.SettlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    @PostMapping("/payments/{paymentId}/refund")
    public PaymentResponse refund(@PathVariable Long paymentId, @RequestParam String reason) {
        Payment payment = refundService.refund(paymentId, reason);
        return paymentMapper.toPaymentResponse(payment);
    }

    @PostMapping("/payments/{paymentId}/dunning")
    public DunningRun dunning(@PathVariable Long paymentId,
                              @RequestParam(defaultValue = "1") int attempt,
                              @RequestParam(required = false) LocalDateTime scheduledAt) {
        return dunningService.scheduleRetry(paymentId, attempt,
                scheduledAt != null ? scheduledAt : LocalDateTime.now().plusHours(1));
    }

    @GetMapping("/dunning/pending")
    public List<DunningRun> pendingDunning() {
        return dunningService.pending();
    }

    @GetMapping("/commission/tiers")
    public List<CommissionTier> tiers() {
        return commissionTierService.active();
    }

    @PostMapping("/commission/tiers")
    public CommissionTier createTier(@RequestParam int minOrderCount,
                                     @RequestParam(required = false) Integer maxOrderCount,
                                     @RequestParam BigDecimal commissionPct) {
        return commissionTierService.create(minOrderCount, maxOrderCount, commissionPct);
    }

    @PostMapping("/settlement")
    public RestaurantSettlementResponse settle(@RequestParam Long restaurantId,
                                               @RequestParam int orderCount,
                                               @RequestParam BigDecimal grossAmount) {
        SettlementService.SettlementResult result = settlementService.run(
                LocalDate.now(), restaurantId, orderCount, grossAmount);
        return paymentMapper.toRestaurantSettlementResponse(result.settlement());
    }

    @PostMapping("/webhook/payment")
    public String webhook(@RequestParam Long paymentId, @RequestParam String event) {
        return "{\"received\":true,\"paymentId\":" + paymentId + ",\"event\":\"" + event + "\"}";
    }
}