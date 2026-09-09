package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.PromotionCampaign;
import com.bhukkad.restaurant.domain.PromotionCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Manages platform-wide promotion campaigns beyond coupon codes.
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.promotion.PromotionCampaignService}. Works against the
 * service-local {@link PromotionCampaign} entity, which uses a simpler
 * schema (single percent + max cap). The monolith keeps a working copy that
 * still drives the legacy controllers until the gateway flips over.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionCampaignService {

    private final PromotionCampaignRepository promotionCampaignRepository;

    /**
     * Lists all currently active promotion campaigns.
     */
    public List<PromotionCampaign> listActive() {
        LocalDateTime now = LocalDateTime.now();
        return promotionCampaignRepository.findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(now, now);
    }

    /**
     * Finds the best applicable discount for a given order subtotal.
     */
    public BigDecimal getBestDiscount(BigDecimal subtotal) {
        if (subtotal == null) {
            return BigDecimal.ZERO;
        }
        Optional<PromotionCampaign> best = listActive().stream()
                .filter(c -> c.getDiscountPct() != null && c.getDiscountPct().signum() > 0)
                .findFirst();

        return best.map(c -> {
                    BigDecimal pct = c.getDiscountPct();
                    BigDecimal discount = subtotal.multiply(pct)
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                    if (c.getMaxDiscount() != null) {
                        discount = discount.min(c.getMaxDiscount());
                    }
                    return discount;
                })
                .orElse(BigDecimal.ZERO);
    }
}