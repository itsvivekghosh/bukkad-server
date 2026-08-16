package com.bhukkad.repository;

import com.bhukkad.entity.DynamicPricingRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalTime;
import java.util.List;

@Repository
public interface DynamicPricingRuleRepository extends JpaRepository<DynamicPricingRule, Long> {

    @Query("SELECT r FROM DynamicPricingRule r WHERE r.restaurant.id = :restaurantId AND r.active = true ORDER BY r.priority DESC")
    List<DynamicPricingRule> findActiveByRestaurant(@Param("restaurantId") Long restaurantId);

    @Query("SELECT r FROM DynamicPricingRule r WHERE r.restaurant.id = :restaurantId AND r.type = :type AND r.active = true")
    List<DynamicPricingRule> findByRestaurantAndType(@Param("restaurantId") Long restaurantId, @Param("type") DynamicPricingRule.RuleType type);

    @Query("SELECT r FROM DynamicPricingRule r WHERE r.restaurant.id = :restaurantId AND r.active = true AND r.startTime <= :time AND r.endTime >= :time")
    List<DynamicPricingRule> findActiveAtTime(@Param("restaurantId") Long restaurantId, @Param("time") LocalTime time);
}