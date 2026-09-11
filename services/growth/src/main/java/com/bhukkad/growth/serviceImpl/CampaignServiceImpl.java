package com.bhukkad.growth.serviceImpl;

import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.CampaignResponse;
import com.bhukkad.growth.entity.PromotionCampaign;
import com.bhukkad.growth.repository.PromotionCampaignRepository;
import com.bhukkad.growth.service.CampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Campaign serving backed by the persisted {@code promotion_campaigns} rows
 * (the {@link PromotionCampaign} entity). The previous implementation read
 * Redis keys nobody ever wrote and returned an always-empty list; the
 * repository is the source of truth for GET /campaigns/active and
 * GET /campaigns/{id}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private final PromotionCampaignRepository campaignRepository;
    private final GrowthProperties growthProperties;

    @Override
    @Transactional(readOnly = true)
    public List<CampaignResponse> getActiveCampaigns() {
        return campaignRepository.findCurrentlyActive(LocalDateTime.now()).stream()
                .map(CampaignServiceImpl::toResponse)
                .toList();
    }

    @Override
    public double calculateBestDiscount(double subtotal) {
        List<CampaignResponse> activeCampaigns = getActiveCampaigns();

        double bestDiscount = 0.0;
        for (CampaignResponse campaign : activeCampaigns) {
            if (campaign.getMinOrderAmount() != null && subtotal < campaign.getMinOrderAmount()) {
                continue;
            }

            double discount = calculateDiscountForCampaign(subtotal, campaign);
            if (discount > bestDiscount) {
                bestDiscount = discount;
            }
        }

        return BigDecimal.valueOf(bestDiscount)
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignResponse getCampaignById(Long campaignId) {
        if (campaignId == null) {
            return null;
        }
        return campaignRepository.findById(campaignId)
                .map(CampaignServiceImpl::toResponse)
                .orElse(null);
    }

    private double calculateDiscountForCampaign(double subtotal, CampaignResponse campaign) {
        double discount = 0.0;

        if (campaign.getDiscountPercent() != null && campaign.getDiscountPercent() > 0) {
            discount = subtotal * (campaign.getDiscountPercent() / 100.0);
        } else if (campaign.getFlatDiscountAmount() != null) {
            discount = campaign.getFlatDiscountAmount();
        }

        if (campaign.getMaxDiscountAmount() != null && discount > campaign.getMaxDiscountAmount()) {
            discount = campaign.getMaxDiscountAmount();
        }

        int maxDiscountPercent = growthProperties.getCampaign().getMaxDiscountPercent();
        double maxAllowed = subtotal * (maxDiscountPercent / 100.0);
        if (discount > maxAllowed) {
            discount = maxAllowed;
        }

        return discount;
    }

    /** Maps the persisted campaign onto the API response (null-tolerant numerics). */
    static CampaignResponse toResponse(PromotionCampaign campaign) {
        return CampaignResponse.builder()
                .id(campaign.getId())
                .name(campaign.getName())
                .campaignType(campaign.getCampaignType())
                .description(campaign.getDescription())
                .discountPercent(toDouble(campaign.getDiscountPercent()))
                .flatDiscountAmount(toDouble(campaign.getFlatDiscountAmount()))
                .minOrderAmount(toDouble(campaign.getMinOrderAmount()))
                .maxDiscountAmount(toDouble(campaign.getMaxDiscountAmount()))
                .freeDelivery(campaign.isFreeDelivery())
                .priority(campaign.getPriority())
                .isActive(campaign.isActive())
                .startsAt(campaign.getStartsAt())
                .endsAt(campaign.getEndsAt())
                .buyQuantity(campaign.getBuyQuantity())
                .getQuantity(campaign.getGetQuantity())
                .getDiscountPercent(campaign.getGetDiscountPercent())
                .targetSegment(campaign.getTargetSegment())
                .build();
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
