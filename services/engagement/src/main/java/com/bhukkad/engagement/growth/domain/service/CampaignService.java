package com.bhukkad.engagement.growth.domain.service;

import com.bhukkad.engagement.growth.api.dto.response.CampaignResponse;

import java.util.List;

public interface CampaignService {

    List<CampaignResponse> getActiveCampaigns();

    double calculateBestDiscount(double subtotal);

    CampaignResponse getCampaignById(Long campaignId);
}
