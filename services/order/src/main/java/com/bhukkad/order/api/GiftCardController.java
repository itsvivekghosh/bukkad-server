package com.bhukkad.order.api;

import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.GiftCard;
import com.bhukkad.order.service.SocialOrderService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Gift-card endpoints (port of monolith's {@code GiftCardController}).
 *
 * <p>Issuance is ADMIN-only and records the issuing account: without the gate
 * any customer could mint unlimited store credit. Redeem is self-service but
 * atomically decremented (see {@code GiftCardRepository.redeem}).</p>
 */
@RestController
@RequestMapping("/api/v1/gift-cards")
@RequiredArgsConstructor
public class GiftCardController {

    private final SocialOrderService socialService;

    @PostMapping("/issue")
    @PreAuthorize("hasRole('ADMIN')")
    public GiftCard issue(@AuthenticationPrincipal TokenPrincipal principal,
                          @RequestParam @Positive BigDecimal amount) {
        return socialService.issueGiftCard(principal == null ? null : principal.userId(), amount);
    }

    @PostMapping("/redeem")
    public Object redeem(@AuthenticationPrincipal TokenPrincipal principal,
                         @org.springframework.web.bind.annotation.RequestBody(required = false)
                         java.util.Map<String, Object> body) {
        // Redeem requires an authenticated redeemer: a bearer-code theft must
        // still land on a user (attribution + one-spend-per-session), and the
        // audit requires it. Guard BEFORE any balance lookup.
        PrincipalGuard.requireAuthenticated(principal);
        // Monolith parity: the apps post {"code": "...", "amount": N}. Omitting
        // the amount redeems the FULL remaining balance.
        String code = body == null ? null : asString(body.get("code"));
        BigDecimal amount = body == null ? null : asBigDecimalOrFullBalance(body.get("amount"));
        if (code == null || code.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("code is required");
        }
        Long redeemer = principal.userId();
        BigDecimal remaining = socialService.redeemGiftCard(code.trim(), redeemer, amount);
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("code", code.trim());
        resp.put("redeemed", true);
        resp.put("remainingBalance", remaining);
        return resp;
    }

    /**
     * Balance/status lookup for a single gift card by code. Returns a masked
     * view — the entity's {@code purchaserId} (customer PII) never leaves.
     */
    public record CodeView(String code, String status, BigDecimal balance, BigDecimal amount) {
    }

    @GetMapping("/{code}")
    public CodeView byCode(@org.springframework.web.bind.annotation.PathVariable String code) {
        GiftCard card = socialService.giftCardByCode(code);
        if (card == null) {
            throw new com.bhukkad.common.error.ResourceNotFoundException(
                    "Gift card not found: " + code);
        }
        return new CodeView(card.getCode(), card.getStatus(), card.getBalance(), card.getAmount());
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal asBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new com.bhukkad.common.error.BusinessException("amount must be numeric");
        }
    }

    private static BigDecimal asBigDecimalOrFullBalance(Object v) {
        return v == null ? null : asBigDecimal(v);
    }
}
