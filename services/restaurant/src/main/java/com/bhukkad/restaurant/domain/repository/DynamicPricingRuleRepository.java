package com.bhukkad.restaurant.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.restaurant.domain.entity.DynamicPricingRule;

public interface DynamicPricingRuleRepository extends JpaRepository<DynamicPricingRule, Long> {
    List<DynamicPricingRule> findByRestaurantIdAndActiveTrue(Long restaurantId);
}