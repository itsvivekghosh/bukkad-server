package com.bhukkad.promotion;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.PromotionCampaign;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.CampaignUsageRepository;
import com.bhukkad.repository.PromotionCampaignRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Promotions engine with stacking rules, usage limits, and best-discount selection (V15).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionEngineService {

    private final PromotionCampaignRepository promotionCampaignRepository;
    private final CampaignUsageRepository campaignUsageRepository;

    /**
     * Evaluates all active campaigns and returns the best applicable discount.
     */
    public PromotionDiscountResult evaluateBestDiscount(Customer customer, Restaurant restaurant, double subtotal) {
        List<PromotionCampaign> campaigns = promotionCampaignRepository.findActiveCampaigns(LocalDateTime.now());
        Optional<PromotionDiscountResult> best = campaigns.stream()
                .filter(c -> isEligible(c, customer, restaurant, subtotal))
                .map(c -> toDiscountResult(c, subtotal))
                .max(Comparator.comparingDouble(PromotionDiscountResult::totalDiscount));

        return best.orElse(PromotionDiscountResult.none());
    }

    /**
     * Records campaign usage after order placement.
     */
    @Transactional
    public void recordUsage(PromotionCampaign campaign, Customer customer, Order order) {
        if (campaign == null) return;
        var usage = new com.bhukkad.entity.CampaignUsage();
        usage.setCampaign(campaign);
        usage.setCustomer(customer);
        usage.setOrder(order);
        campaignUsageRepository.save(usage);
    }

    private boolean isEligible(PromotionCampaign campaign, Customer customer, Restaurant restaurant, double subtotal) {
        if (campaign.getMinOrderAmount() != null && subtotal < campaign.getMinOrderAmount()) {
            return false;
        }
        if (campaign.getRestaurant() != null
                && !campaign.getRestaurant().getId().equals(restaurant.getId())) {
            return false;
        }
        if (campaign.getUsageLimit() != null
                && campaignUsageRepository.countByCampaignId(campaign.getId()) >= campaign.getUsageLimit()) {
            return false;
        }
        int perUser = campaign.getPerUserLimit() != null ? campaign.getPerUserLimit() : 1;
        if (campaignUsageRepository.countByCampaignIdAndCustomerId(campaign.getId(), customer.getId()) >= perUser) {
            return false;
        }
        return true;
    }

    private PromotionDiscountResult toDiscountResult(PromotionCampaign campaign, double subtotal) {
        double discount = 0.0;
        boolean freeDelivery = Boolean.TRUE.equals(campaign.getFreeDelivery());

        if (campaign.getFlatDiscountAmount() != null && campaign.getFlatDiscountAmount() > 0) {
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
}
