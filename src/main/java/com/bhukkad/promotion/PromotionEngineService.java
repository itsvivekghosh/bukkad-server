package com.bhukkad.promotion;

import com.bhukkad.entity.CartItem;
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
 * Promotions engine with stacking rules, usage limits, user segments, and
 * Buy-X-Get-Y offers, selecting the best applicable discount (V15+).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionEngineService {

    /** Customers registered within this many days count as NEW_USER segment. */
    private static final int NEW_USER_DAYS = 30;

    private final PromotionCampaignRepository promotionCampaignRepository;
    private final CampaignUsageRepository campaignUsageRepository;

    /**
     * Evaluates all active campaigns for a subtotal-only context (free delivery checks).
     */
    public PromotionDiscountResult evaluateBestDiscount(Customer customer, Restaurant restaurant, double subtotal) {
        return evaluateBestDiscount(customer, restaurant, subtotal, List.of());
    }

    /**
     * Evaluates all active campaigns and returns the best applicable discount.
     *
     * @param cartItems current cart lines; required for Buy-X-Get-Y evaluation
     */
    public PromotionDiscountResult evaluateBestDiscount(Customer customer, Restaurant restaurant,
                                                        double subtotal, List<CartItem> cartItems) {
        List<PromotionCampaign> campaigns = promotionCampaignRepository.findActiveCampaigns(LocalDateTime.now());
        Optional<PromotionDiscountResult> best = campaigns.stream()
                .filter(c -> isEligible(c, customer, restaurant, subtotal))
                .map(c -> toDiscountResult(c, customer, subtotal, cartItems))
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
        if (!matchesSegment(campaign, customer)) {
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

    private boolean matchesSegment(PromotionCampaign campaign, Customer customer) {
        PromotionCampaign.CampaignSegment segment = campaign.getTargetSegment();
        if (segment == null || PromotionCampaign.CampaignSegment.ALL.equals(segment)) {
            return true;
        }
        if (customer == null) {
            return false;
        }
        return switch (segment) {
            case NEW_USER -> customer.getCreatedAt() != null
                    && customer.getCreatedAt().isAfter(LocalDateTime.now().minusDays(NEW_USER_DAYS));
            case VIP -> customer.getLoyaltyPoints() != null && customer.getLoyaltyPoints() >= 1000;
            default -> true;
        };
    }

    private PromotionDiscountResult toDiscountResult(PromotionCampaign campaign, Customer customer,
                                                     double subtotal, List<CartItem> cartItems) {
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

    /**
     * Buy X Get Y: for each item line, every {@code buyQuantity} units unlock
     * {@code getQuantity} units at {@code getDiscountPercent}% off their price.
     * When the campaign targets a specific menu item, only that item counts.
     */
    private double computeBuyXGetYDiscount(PromotionCampaign campaign, List<CartItem> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            return 0.0;
        }
        double discount = 0.0;
        for (CartItem item : cartItems) {
            if (item.getMenuItem() == null || item.getMenuItem().getPrice() == null) {
                continue;
            }
            if (campaign.getApplicableMenuItem() != null
                    && !campaign.getApplicableMenuItem().getId().equals(item.getMenuItem().getId())) {
                continue;
            }
            int quantity = item.getQuantity() != null ? item.getQuantity() : 0;
            if (quantity < campaign.getBuyQuantity()) {
                continue;
            }
            int cycles = quantity / campaign.getBuyQuantity();
            int freeUnits = Math.min(campaign.getGetQuantity() * cycles, quantity);
            double pricePerUnit = item.getMenuItem().getPrice();
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
}
