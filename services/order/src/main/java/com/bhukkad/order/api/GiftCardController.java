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
                             @RequestParam String code,
                             @RequestParam @Positive BigDecimal amount) {
        PrincipalGuard.requireAuthenticated(principal);
        return socialService.redeemGiftCard(code, principal.userId(), amount);
    }
}
