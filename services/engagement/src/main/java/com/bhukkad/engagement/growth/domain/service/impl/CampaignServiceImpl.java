package com.bhukkad.engagement.growth.domain.service.impl;

import com.bhukkad.engagement.growth.config.GrowthProperties;
import com.bhukkad.engagement.growth.api.dto.response.CampaignResponse;
import com.bhukkad.engagement.growth.domain.entity.PromotionCampaign;
import com.bhukkad.engagement.growth.domain.repository.PromotionCampaignRepository;
import com.bhukkad.engagement.growth.domain.service.CampaignService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Campaigns are served straight from the {@code promotion_campaigns} table
 * (the campaign back-office is the writer; P-08 left no Redis projection and
 * this module contains no writer for the old {@code growth:campaign*} keys —
 * reading them in P0 always produced an empty list).
 * 
 * <p>Optimizations:
 * <ul>
 *   <li>Caffeine cache for active campaigns (60s TTL, 1000 entries)</li>
 *   <li>Discount calculation cache keyed by subtotal + campaign IDs</li>
 *   <li>Cache invalidation on campaign state changes</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private final PromotionCampaignRepository campaignRepository;
    private final GrowthProperties growthProperties;

    // Caffeine cache for active campaigns - 60s TTL, bounded to 1000 entries
    private final Cache<LocalDateTime, List<CampaignResponse>> activeCampaignsCache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(Duration.ofSeconds(60))
        .build();

    // Discount calculation cache - keyed by subtotal rounded to nearest rupee + timestamp bucket
    private final Cache<String, Double> discountCalculationCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .build();

    @Override
    @Transactional(readOnly = true)
    public List<CampaignResponse> getActiveCampaigns() {
        LocalDateTime now = LocalDateTime.now();
        // Round to nearest minute to improve cache hit rate
        LocalDateTime cacheKey = now.withSecond(0).withNano(0);
        
        return activeCampaignsCache.get(cacheKey, key -> {
            log.debug("CAMPAIGN_CACHE_MISS | key={}", key);
            return campaignRepository.findActiveCampaigns(now).stream()
                    .map(CampaignServiceImpl::toResponse)
                    .toList();
        });
    }

    @Override
    @Transactional(readOnly = true)
    public double calculateBestDiscount(double subtotal) {
        // Round subtotal to nearest rupee for cache key
        long subtotalCents = Math.round(subtotal * 100);
        String cacheKey = "discount:" + subtotalCents + ":" + LocalDateTime.now().withSecond(0);
        
        return discountCalculationCache.get(cacheKey, key -> {
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
        });
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignResponse getCampaignById(Long campaignId) {
        Optional<PromotionCampaign> campaign =
                campaignRepository.findActiveCampaign(campaignId, LocalDateTime.now());
        return campaign.map(CampaignServiceImpl::toResponse).orElse(null);
    }

    /**
     * Invalidates campaign caches. Call this when campaigns are created/updated/deactivated.
     */
    public void invalidateCampaignCache() {
        activeCampaignsCache.invalidateAll();
        discountCalculationCache.invalidateAll();
        log.info("CAMPAIGN_CACHE_INVALIDATED");
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
