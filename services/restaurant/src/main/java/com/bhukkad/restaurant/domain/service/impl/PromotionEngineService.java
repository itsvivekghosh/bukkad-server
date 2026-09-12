package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.common.util.PriceCalculator;
import com.bhukkad.restaurant.domain.entity.CampaignUsage;
import com.bhukkad.restaurant.domain.repository.CampaignUsageRepository;
import com.bhukkad.restaurant.domain.entity.PromotionCampaign;
import com.bhukkad.restaurant.domain.repository.PromotionCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PromotionEngineService {

    private static final int NEW_USER_DAYS = 30;

    private final PromotionCampaignRepository promotionCampaignRepository;
    private final CampaignUsageRepository campaignUsageRepository;

    @Transactional(readOnly = true)
    public PromotionDiscountResult evaluateBestDiscount(Long restaurantId, double subtotal) {
        return evaluateBestDiscount(restaurantId, subtotal, List.of());
    }

    @Transactional(readOnly = true)
    public PromotionDiscountResult evaluateBestDiscount(Long restaurantId, double subtotal,
                                                        List<CartItemDto> cartItems) {
        return evaluateBestDiscount(restaurantId, subtotal, null, cartItems);
    }

    @Transactional(readOnly = true)
    public PromotionDiscountResult evaluateBestDiscount(Long restaurantId, double subtotal, Long customerId,
                                                        List<CartItemDto> cartItems) {
        List<PromotionCampaign> campaigns = promotionCampaignRepository.findActiveCampaigns(LocalDateTime.now());
        Optional<PromotionDiscountResult> best = campaigns.stream()
                .filter(c -> isEligible(c, restaurantId, subtotal, customerId))
                .map(c -> toDiscountResult(c, subtotal, cartItems))
                .max(Comparator.comparingDouble(PromotionDiscountResult::totalDiscount));

        return best.orElse(PromotionDiscountResult.none());
    }

    @Transactional
    public void recordUsage(Long campaignId, Long customerId, Long orderId) {
        if (campaignId == null) return;
        var usage = new CampaignUsage();
        usage.setCampaignId(campaignId);
        usage.setCustomerId(customerId);
        usage.setOrderId(orderId);
        campaignUsageRepository.save(usage);
    }

    private boolean isEligible(PromotionCampaign campaign, Long restaurantId, double subtotal, Long customerId) {
        if (campaign.getMinOrderAmount() != null && subtotal < campaign.getMinOrderAmount()) {
            return false;
        }
        if (campaign.getRestaurantId() != null
                && !campaign.getRestaurantId().equals(restaurantId)) {
            return false;
        }
        if (campaign.getUsageLimit() != null
                && campaignUsageRepository.countByCampaignId(campaign.getId()) >= campaign.getUsageLimit()) {
            return false;
        }
        int perUser = campaign.getPerUserLimit() != null ? campaign.getPerUserLimit() : 1;
        if (customerId != null
                && campaignUsageRepository.countByCampaignIdAndCustomerId(campaign.getId(), customerId) >= perUser) {
            return false;
        }
        return true;
    }

    private PromotionDiscountResult toDiscountResult(PromotionCampaign campaign, double subtotal,
                                                     List<CartItemDto> cartItems) {
        double discount = 0.0;
        boolean freeDelivery = Boolean.TRUE.equals(campaign.getFreeDelivery());

        if (isBuyXGetY(campaign)) {
            discount = computeBuyXGetYDiscount(campaign, cartItems);
        } else if (campaign.getFlatDiscountAmount() != null && campaign.getFlatDiscountAmount() > 0) {
            discount = campaign.getFlatDiscountAmount();
        } else if (campaign.getDiscountPercent() != null && campaign.getDiscountPercent() > 0) {
            discount = PriceCalculator.calculateDiscount(subtotal, campaign.getDiscountPercent());
            if (campaign.getMaxDiscountAmount() != null) {
                discount = Math.min(discount, campaign.getMaxDiscountAmount());
            }
        }
        discount = PriceCalculator.roundToTwoDecimals(discount);
        return new PromotionDiscountResult(campaign, discount, freeDelivery);
    }

    private boolean isBuyXGetY(PromotionCampaign campaign) {
        return campaign.getBuyQuantity() != null && campaign.getBuyQuantity() > 0
                && campaign.getGetQuantity() != null && campaign.getGetQuantity() > 0
                && campaign.getGetDiscountPercent() != null;
    }

    private double computeBuyXGetYDiscount(PromotionCampaign campaign, List<CartItemDto> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            return 0.0;
        }
        double discount = 0.0;
        for (CartItemDto item : cartItems) {
            if (item.menuItemId() == null || item.price() == null) {
                continue;
            }
            if (campaign.getApplicableMenuItemId() != null
                    && !campaign.getApplicableMenuItemId().equals(item.menuItemId())) {
                continue;
            }
            int quantity = item.quantity() != null ? item.quantity() : 0;
            if (quantity < campaign.getBuyQuantity()) {
                continue;
            }
            int cycles = quantity / campaign.getBuyQuantity();
            int freeUnits = Math.min(campaign.getGetQuantity() * cycles, quantity);
            double pricePerUnit = item.price();
            double percentOff = campaign.getGetDiscountPercent() / 100.0;
            discount += freeUnits * pricePerUnit * percentOff;
        }
        return discount;
    }

    public record PromotionDiscountResult(
            PromotionCampaign campaign,
            double discountAmount,
            boolean freeDelivery) {

        public double totalDiscount() {
            return discountAmount;
        }

        public static PromotionDiscountResult none() {
            return new PromotionDiscountResult(null, 0.0, false);
        }
    }

    public record CartItemDto(Long menuItemId, Double price, Integer quantity) {
    }
}
