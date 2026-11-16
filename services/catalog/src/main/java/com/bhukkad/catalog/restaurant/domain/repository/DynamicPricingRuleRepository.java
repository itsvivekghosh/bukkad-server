package com.bhukkad.catalog.restaurant.domain.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import com.bhukkad.catalog.restaurant.domain.entity.DynamicPricingRule;

public interface DynamicPricingRuleRepository extends JpaRepository<DynamicPricingRule, Long> {
    Page<DynamicPricingRule> findByRestaurantIdAndActiveTrue(Long restaurantId, Pageable pageable);
    List<DynamicPricingRule> findByRestaurantIdAndActiveTrue(Long restaurantId);
}
