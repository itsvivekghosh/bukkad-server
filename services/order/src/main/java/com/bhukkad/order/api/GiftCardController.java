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
    public BigDecimal redeem(@AuthenticationPrincipal TokenPrincipal principal,
                             @org.springframework.web.bind.annotation.RequestBody(required = false)
                             java.util.Map<String, Object> body) {
        // Monolith parity: the apps post {"code": "...", "amount": N}; the
        // query-param form is kept for backwards compatibility.
        String code = body == null ? null : asString(body.get("code"));
        BigDecimal amount = body == null ? null : asBigDecimal(body.get("amount"));
        if (code == null || code.isBlank()) {
            throw new com.bhukkad.common.error.BusinessException("code is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new com.bhukkad.common.error.BusinessException("amount must be positive");
        }
        Long redeemer = principal == null ? null : principal.userId();
        return socialService.redeemGiftCard(code.trim(), redeemer, amount);
    }

    /** Balance/status lookup for a single gift card by code. */
    @GetMapping("/{code}")
    public GiftCard byCode(@org.springframework.web.bind.annotation.PathVariable String code) {
        GiftCard card = socialService.giftCardByCode(code);
        if (card == null) {
            throw new com.bhukkad.common.error.ResourceNotFoundException(
                    "Gift card not found: " + code);
        }
        return card;
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
}
