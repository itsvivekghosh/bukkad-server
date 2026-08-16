package com.bhukkad.service;

import com.bhukkad.dto.response.CommissionTierResponse;
import com.bhukkad.entity.Restaurant;

import java.util.List;
import java.util.Optional;

public interface CommissionTierService {
    CommissionTierResponse calculateCommission(Long restaurantId);
    List<CommissionTierResponse> getCommissionTiers();
    void updateCommissionTiers();
    double getEffectiveCommissionRate(Long restaurantId);
}