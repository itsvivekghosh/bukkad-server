package com.bhukkad.growth.serviceImpl;

import com.bhukkad.growth.config.GrowthProperties;
import com.bhukkad.growth.dto.CampaignResponse;
import com.bhukkad.growth.service.CampaignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignServiceImpl implements CampaignService {

    private static final String CAMPAIGNS_KEY = "growth:campaigns:active";

    private final StringRedisTemplate redisTemplate;
    private final GrowthProperties growthProperties;

    @Override
    public List<CampaignResponse> getActiveCampaigns() {
        List<CampaignResponse> campaigns = new ArrayList<>();

        // Get campaigns from Redis sorted set (sorted by priority descending)
        var campaignIds = redisTemplate.opsForZSet().reverseRange(CAMPAIGNS_KEY, 0, -1);

        if (campaignIds == null || campaignIds.isEmpty()) {
            return campaigns;
        }

        for (String campaignId : campaignIds) {
            String campaignJson = (String) redisTemplate.opsForHash().get("growth:campaign:" + campaignId, "data");
            if (campaignJson != null) {
                // In real implementation, deserialize JSON to CampaignResponse
                // For now, return basic structure
            }
        }

        return campaigns;
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
    public CampaignResponse getCampaignById(Long campaignId) {
        String campaignJson = (String) redisTemplate.opsForHash().get("growth:campaign:" + campaignId, "data");
        // Deserialize and return
        return null;
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
}
