package com.bhukkad.delivery.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.delivery.service.DeliveryOpsService;
import com.bhukkad.delivery.service.RiderOpsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Rider COD wallet and earnings endpoints.
 *
 * <p>COD wallet balance/credit/debit and rider earnings are **owned by the
 * payment service** ({@code DeliveryPaymentController}). This service is a
 * thin REST façade that delegates to the payment service via
 * {@link com.bhukkad.delivery.client.PaymentServiceClient}, preserving the
 * single-writer money boundary.</p>
 *
 * <p>Mutations are ADMIN-only, and rider-scoped reads enforce self-or-admin:
 * previously any customer JWT could drain or mint any rider's money by
 * changing the path id.</p>
 */
@RestController
@RequestMapping("/api/v1/deliveries")
@RequiredArgsConstructor
@Validated
public class RiderWalletController {

    private final RiderOpsService riderOpsService;
    private final DeliveryOpsService deliveryOpsService;

    // ============= COD Wallet =============

    @GetMapping("/riders/{agentId}/cod-wallet")
    public ResponseEntity<Map<String, Object>> getCodWallet(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long agentId) {
        PrincipalGuard.requireSelfOrAdmin(principal, agentId);
        Map<String, Object> wallet = riderOpsService.getCodWallet(agentId);
        return ResponseEntity.ok(wallet);
    }

    @PostMapping("/riders/{agentId}/cod-wallet/credit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> creditCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {
        Map<String, Object> wallet = riderOpsService.creditCod(agentId, amount);
        return ResponseEntity.ok(wallet);
    }

    @PostMapping("/riders/{agentId}/cod-wallet/debit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> debitCodWallet(
            @PathVariable Long agentId,
            @RequestParam BigDecimal amount) {
        Map<String, Object> wallet = riderOpsService.debitCod(agentId, amount);
        return ResponseEntity.ok(wallet);
    }

    // ============= Rider Earnings =============

    @PostMapping("/earnings")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> recordEarning(
            @RequestParam Long agentId,
            @RequestParam Long orderId,
            @RequestParam(required = false) BigDecimal amount) {
        Map<String, Object> earning;
        if (amount != null) {
            // Allow an explicit amount override (e.g. surge-adjusted earnings).
            earning = deliveryOpsService.recordEarning(agentId, orderId, amount);
        } else {
            // Default to the configured per-delivery rate (delegated to
            // RiderOpsService which reads RiderEarningsProperties).
            earning = riderOpsService.recordEarning(agentId, orderId);
        }
        return ResponseEntity.ok(earning);
    }

    @GetMapping("/riders/{agentId}/earnings")
    public ResponseEntity<List<Map<String, Object>>> getEarnings(
            @AuthenticationPrincipal TokenPrincipal principal,
            @PathVariable Long agentId) {
        PrincipalGuard.requireSelfOrAdmin(principal, agentId);
        List<Map<String, Object>> earnings = riderOpsService.getEarnings(agentId);
        return ResponseEntity.ok(earnings);
    }

    @PostMapping("/earnings/{earningId}/mark-paid")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> markEarningPaid(@PathVariable Long earningId) {
        Map<String, Object> result = riderOpsService.markEarningPaid(earningId);
        return ResponseEntity.ok(result);
    }
}
