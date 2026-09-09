package com.bhukkad.growth.service;

import com.bhukkad.growth.dto.CampaignResponse;

import java.util.List;

public interface CampaignService {

    List<CampaignResponse> getActiveCampaigns();

    double calculateBestDiscount(double subtotal);

    CampaignResponse getCampaignById(Long campaignId);
}
