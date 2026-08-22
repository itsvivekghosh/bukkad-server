package com.bhukkad.promotion;

import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.entity.PromotionCampaign;
import com.bhukkad.repository.PromotionCampaignRepository;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Manages platform-wide promotion campaigns beyond coupon codes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionCampaignService {

    private final PromotionCampaignRepository promotionCampaignRepository;

    /**
     * Lists all currently active promotion campaigns.
     *
     * @return active campaigns
     */
    public List<PromotionCampaignResponse> listActive() {
        return promotionCampaignRepository.findActiveCampaigns(LocalDateTime.now()).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Finds the best applicable discount for a given order subtotal.
     *
     * @param subtotal order subtotal
     * @return discount amount, or zero if no campaign applies
     */
    public double getBestDiscount(double subtotal) {
        Optional<PromotionCampaign> best = promotionCampaignRepository.findActiveCampaigns(LocalDateTime.now())
                .stream()
                .filter(campaign -> campaign.getDiscountPercent() != null && campaign.getDiscountPercent() > 0)
                .filter(campaign -> campaign.getMinOrderAmount() == null || subtotal >= campaign.getMinOrderAmount())
                .findFirst();

        return best.map(campaign -> PriceCalculator.roundToTwoDecimals(
                        PriceCalculator.calculateDiscount(subtotal, campaign.getDiscountPercent())))
                .orElse(0.0);
    }

    private PromotionCampaignResponse toResponse(PromotionCampaign campaign) {
        return PromotionCampaignResponse.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .campaignType(campaign.getCampaignType())
                .description(campaign.getDescription())
                .discountPercent(campaign.getDiscountPercent())
                .flatDiscountAmount(campaign.getFlatDiscountAmount())
                .minOrderAmount(campaign.getMinOrderAmount())
                .maxDiscountAmount(campaign.getMaxDiscountAmount())
                .restaurantId(campaign.getRestaurant() != null ? campaign.getRestaurant().getId() : null)
                .freeDelivery(campaign.getFreeDelivery())
                .priority(campaign.getPriority())
                .isActive(campaign.getIsActive())
                .startsAt(campaign.getStartsAt() != null ? campaign.getStartsAt().toString() : null)
                .endsAt(campaign.getEndsAt() != null ? campaign.getEndsAt().toString() : null)
                .buyQuantity(campaign.getBuyQuantity())
                .getQuantity(campaign.getGetQuantity())
                .getDiscountPercent(campaign.getGetDiscountPercent())
                .targetSegment(campaign.getTargetSegment() != null ? campaign.getTargetSegment().name() : null)
                .applicableMenuItemId(campaign.getApplicableMenuItem() != null
                        ? campaign.getApplicableMenuItem().getId() : null)
                .build();
    }
}
