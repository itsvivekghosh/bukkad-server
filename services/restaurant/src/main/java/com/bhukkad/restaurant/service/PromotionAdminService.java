package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.PromotionCampaign;
import com.bhukkad.restaurant.domain.PromotionCampaignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin CRUD for promotion campaigns.
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.promotion.PromotionAdminService}. Operates on the
 * service-local {@link PromotionCampaign} entity which uses a simplified
 * schema. The monolith keeps the working copy with the full feature set
 * (Buy-X-Get-Y, segments, etc.) until the gateway flips over.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionAdminService {

    private final PromotionCampaignRepository promotionCampaignRepository;

    public List<PromotionCampaign> listAll() {
        return promotionCampaignRepository.findAll();
    }

    @Transactional
    public PromotionCampaign create(String name, String description, BigDecimal discountPct,
                                    BigDecimal maxDiscount, LocalDateTime startsAt, LocalDateTime endsAt,
                                    Boolean active) {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.setName(name);
        campaign.setDescription(description);
        campaign.setDiscountPct(discountPct);
        campaign.setMaxDiscount(maxDiscount);
        campaign.setStartsAt(startsAt);
        campaign.setEndsAt(endsAt);
        campaign.setActive(active != null ? active : Boolean.TRUE);
        return promotionCampaignRepository.save(campaign);
    }

    @Transactional
    public PromotionCampaign update(Long id, String name, String description, BigDecimal discountPct,
                                    BigDecimal maxDiscount, LocalDateTime startsAt, LocalDateTime endsAt,
                                    Boolean active) {
        PromotionCampaign campaign = findOrThrow(id);
        if (name != null) campaign.setName(name);
        if (description != null) campaign.setDescription(description);
        if (discountPct != null) campaign.setDiscountPct(discountPct);
        if (maxDiscount != null) campaign.setMaxDiscount(maxDiscount);
        if (startsAt != null) campaign.setStartsAt(startsAt);
        if (endsAt != null) campaign.setEndsAt(endsAt);
        if (active != null) campaign.setActive(active);
        return promotionCampaignRepository.save(campaign);
    }

    @Transactional
    public void deactivate(Long id) {
        PromotionCampaign campaign = findOrThrow(id);
        campaign.setActive(false);
        promotionCampaignRepository.save(campaign);
    }

    private PromotionCampaign findOrThrow(Long id) {
        return promotionCampaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion campaign not found: " + id));
    }
}