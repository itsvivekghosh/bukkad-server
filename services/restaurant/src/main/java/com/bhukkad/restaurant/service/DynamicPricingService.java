package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.DynamicPricingRule;
import com.bhukkad.restaurant.domain.DynamicPricingRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Surge/promotional pricing rules (port of monolith
 * {@code DynamicPricingServiceImpl}).
 */
@Service
@RequiredArgsConstructor
public class DynamicPricingService {

    private final DynamicPricingRuleRepository ruleRepository;

    @Transactional
    public DynamicPricingRule create(Long restaurantId, String name, BigDecimal multiplier,
                                     LocalTime startTime, LocalTime endTime) {
        if (multiplier.signum() <= 0) {
            throw new com.bhukkad.common.error.BusinessException("Multiplier must be positive");
        }
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setRestaurantId(restaurantId);
        rule.setRuleName(name);
        rule.setMultiplier(multiplier);
        rule.setStartTime(startTime);
        rule.setEndTime(endTime);
        rule.setActive(true);
        return ruleRepository.save(rule);
    }

    @Transactional(readOnly = true)
    public List<DynamicPricingRule> active(Long restaurantId) {
        return ruleRepository.findByRestaurantIdAndActiveTrue(restaurantId);
    }

    @Transactional
    public void deactivate(Long ruleId) {
        DynamicPricingRule rule = ruleRepository.findById(ruleId).orElseThrow();
        rule.setActive(false);
        ruleRepository.save(rule);
    }
}