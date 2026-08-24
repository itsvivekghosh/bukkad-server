package com.bhukkad.service;

import com.bhukkad.dto.response.CommissionTierResponse;

import java.util.List;

public interface CommissionTierService {
    CommissionTierResponse calculateCommission(Long restaurantId);
    List<CommissionTierResponse> getCommissionTiers();
    void updateCommissionTiers();
    double getEffectiveCommissionRate(Long restaurantId);
}