package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DynamicPricingRuleRepository extends JpaRepository<DynamicPricingRule, Long> {
    List<DynamicPricingRule> findByRestaurantIdAndActiveTrue(Long restaurantId);
}