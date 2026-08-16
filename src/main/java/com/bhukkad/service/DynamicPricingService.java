package com.bhukkad.service;

import com.bhukkad.dto.request.DynamicPricingRuleRequest;
import com.bhukkad.dto.response.DynamicPricingRuleResponse;
import com.bhukkad.entity.DynamicPricingRule;

import java.util.List;

public interface DynamicPricingService {
    DynamicPricingRuleResponse createRule(Long restaurantId, DynamicPricingRuleRequest request);
    DynamicPricingRuleResponse updateRule(Long ruleId, DynamicPricingRuleRequest request);
    void deleteRule(Long ruleId);
    List<DynamicPricingRuleResponse> getRulesByRestaurant(Long restaurantId);
    double calculateDynamicPrice(Long restaurantId, double basePrice, double subtotal);
    boolean isHappyHourActive(Long restaurantId);
}