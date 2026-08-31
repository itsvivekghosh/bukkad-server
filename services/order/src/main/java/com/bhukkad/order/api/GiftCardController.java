package com.bhukkad.order.api;

import com.bhukkad.order.domain.GiftCard;
import com.bhukkad.order.service.SocialOrderService;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * Gift-card endpoints (port of monolith's {@code GiftCardController}).
 */
@RestController
@RequestMapping("/api/v1/gift-cards")
@RequiredArgsConstructor
public class GiftCardController {

    private final SocialOrderService socialService;

    @PostMapping("/issue")
    public GiftCard issue(@RequestParam @Positive BigDecimal amount) {
        return socialService.issueGiftCard(amount);
    }

    @PostMapping("/redeem")
    public BigDecimal redeem(@RequestParam String code, @RequestParam @Positive BigDecimal amount) {
        return socialService.redeemGiftCard(code, amount);
    }
}