package com.bhukkad.promotion;

import com.bhukkad.dto.request.PromotionCampaignRequest;
import com.bhukkad.dto.response.PromotionCampaignResponse;
import com.bhukkad.entity.PromotionCampaign;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.PromotionCampaignRepository;
import com.bhukkad.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Admin CRUD for promotion campaigns (V15).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionAdminService {

    private final PromotionCampaignRepository promotionCampaignRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;

    public List<PromotionCampaignResponse> listAll() {
        return promotionCampaignRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public PromotionCampaignResponse create(PromotionCampaignRequest request) {
        PromotionCampaign campaign = new PromotionCampaign();
        applyRequest(campaign, request);
        return toResponse(promotionCampaignRepository.save(campaign));
    }

    @Transactional
    public PromotionCampaignResponse update(Long id, PromotionCampaignRequest request) {
        PromotionCampaign campaign = findOrThrow(id);
        applyRequest(campaign, request);
        return toResponse(promotionCampaignRepository.save(campaign));
    }

    @Transactional
    public void deactivate(Long id) {
        PromotionCampaign campaign = findOrThrow(id);
        campaign.setIsActive(false);
        promotionCampaignRepository.save(campaign);
    }

    private PromotionCampaign findOrThrow(Long id) {
        return promotionCampaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion campaign not found"));
    }

    private void applyRequest(PromotionCampaign campaign, PromotionCampaignRequest request) {
        if (request.getName() != null) campaign.setName(request.getName());
        if (request.getCampaignType() != null) campaign.setCampaignType(request.getCampaignType());
        if (request.getDescription() != null) campaign.setDescription(request.getDescription());
        if (request.getDiscountPercent() != null) campaign.setDiscountPercent(request.getDiscountPercent());
        if (request.getFlatDiscountAmount() != null) campaign.setFlatDiscountAmount(request.getFlatDiscountAmount());
        if (request.getMinOrderAmount() != null) campaign.setMinOrderAmount(request.getMinOrderAmount());
        if (request.getMaxDiscountAmount() != null) campaign.setMaxDiscountAmount(request.getMaxDiscountAmount());
        if (request.getFreeDelivery() != null) campaign.setFreeDelivery(request.getFreeDelivery());
        if (request.getPriority() != null) campaign.setPriority(request.getPriority());
        if (request.getUsageLimit() != null) campaign.setUsageLimit(request.getUsageLimit());
        if (request.getPerUserLimit() != null) campaign.setPerUserLimit(request.getPerUserLimit());
        if (request.getIsActive() != null) campaign.setIsActive(request.getIsActive());
        if (request.getStartsAt() != null) campaign.setStartsAt(request.getStartsAt());
        if (request.getEndsAt() != null) campaign.setEndsAt(request.getEndsAt());
        if (request.getBuyQuantity() != null) campaign.setBuyQuantity(request.getBuyQuantity());
        if (request.getGetQuantity() != null) campaign.setGetQuantity(request.getGetQuantity());
        if (request.getGetDiscountPercent() != null) campaign.setGetDiscountPercent(request.getGetDiscountPercent());
        if (request.getTargetSegment() != null) {
            try {
                campaign.setTargetSegment(PromotionCampaign.CampaignSegment.valueOf(
                        request.getTargetSegment().trim().toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new com.bhukkad.exception.BusinessException(
                        "Invalid target segment: " + request.getTargetSegment());
            }
        }
        if (request.getRestaurantId() != null) {
            Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
            campaign.setRestaurant(restaurant);
        }
        if (request.getApplicableMenuItemId() != null) {
            com.bhukkad.entity.MenuItem menuItem = menuItemRepository.findById(request.getApplicableMenuItemId())
                    .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));
            campaign.setApplicableMenuItem(menuItem);
        }
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
                .usageLimit(campaign.getUsageLimit())
                .perUserLimit(campaign.getPerUserLimit())
                .startsAt(campaign.getStartsAt() != null ? campaign.getStartsAt().toString() : null)
                .endsAt(campaign.getEndsAt() != null ? campaign.getEndsAt().toString() : null)
                .isActive(campaign.getIsActive())
                .buyQuantity(campaign.getBuyQuantity())
                .getQuantity(campaign.getGetQuantity())
                .getDiscountPercent(campaign.getGetDiscountPercent())
                .targetSegment(campaign.getTargetSegment() != null ? campaign.getTargetSegment().name() : null)
                .applicableMenuItemId(campaign.getApplicableMenuItem() != null
                        ? campaign.getApplicableMenuItem().getId() : null)
                .build();
    }
}
