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
import java.util.Optional;

/**
 * Campaigns are served straight from the {@code promotion_campaigns} table
 * (the campaign back-office is the writer; P-08 left no Redis projection and
 * this module contains no writer for the old {@code growth:campaign*} keys —
 * reading them in P0 always produced an empty list).
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
        return campaignRepository.findActiveCampaigns(LocalDateTime.now()).stream()
                .map(CampaignServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
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
        Optional<PromotionCampaign> campaign =
                campaignRepository.findActiveCampaign(campaignId, LocalDateTime.now());
        // Not found AND deactivated/expired campaigns both keep the 404 path
        // the controller exposes for null.
        return campaign.map(CampaignServiceImpl::toResponse).orElse(null);
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

    /** Entity → API DTO. Decimal money/percent columns widen to Double. */
    static CampaignResponse toResponse(PromotionCampaign c) {
        return CampaignResponse.builder()
                .id(c.getId())
                .name(c.getName())
                .campaignType(c.getCampaignType())
                .description(c.getDescription())
                .discountPercent(toDouble(c.getDiscountPercent()))
                .flatDiscountAmount(toDouble(c.getFlatDiscountAmount()))
                .minOrderAmount(toDouble(c.getMinOrderAmount()))
                .maxDiscountAmount(toDouble(c.getMaxDiscountAmount()))
                .freeDelivery(c.isFreeDelivery())
                .priority(c.getPriority())
                .isActive(c.isActive())
                .startsAt(c.getStartsAt())
                .endsAt(c.getEndsAt())
                .buyQuantity(c.getBuyQuantity())
                .getQuantity(c.getGetQuantity())
                .getDiscountPercent(c.getGetDiscountPercent())
                .targetSegment(c.getTargetSegment())
                .build();
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
