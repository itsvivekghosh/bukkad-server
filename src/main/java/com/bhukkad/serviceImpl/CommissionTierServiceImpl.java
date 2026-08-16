package com.bhukkad.serviceImpl;

import com.bhukkad.config.CommissionTierProperties;
import com.bhukkad.dto.response.CommissionTierResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CommissionTierService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionTierServiceImpl implements CommissionTierService {

    private final CommissionTierProperties commissionTierProperties;
    private final OrderRepository orderRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;

    @Override
    public CommissionTierResponse calculateCommission(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        long orderCount = orderRepository.countByRestaurantIdAndCreatedAtAfter(
                restaurantId, LocalDateTime.now().minusMonths(1));

        double commissionRate = getEffectiveCommissionRate(restaurantId);

        return CommissionTierResponse.builder()
                .tierName(getTierName(orderCount))
                .minOrders(0)
                .maxOrders(getMaxOrdersForTier(orderCount))
                .commissionPercent(commissionRate * 100)
                .description(String.format("Based on %d orders in last 30 days", orderCount))
                .build();
    }

    @Override
    public List<CommissionTierResponse> getCommissionTiers() {
        return commissionTierProperties.getTiers().stream()
                .map(tier -> CommissionTierResponse.builder()
                        .tierName(tier.getName())
                        .minOrders(tier.getMinOrders())
                        .maxOrders(tier.getMaxOrders())
                        .commissionPercent(tier.getCommissionPercent())
                        .description(tier.getDescription())
                        .build())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void updateCommissionTiers() {
        List<Restaurant> restaurants = restaurantRepository.findAll();
        for (Restaurant restaurant : restaurants) {
            double commissionRate = getEffectiveCommissionRate(restaurant.getId());
            restaurant.setCommissionPercent(commissionRate * 100);
            restaurantRepository.save(restaurant);
        }
        log.info("Updated commission tiers for {} restaurants", restaurants.size());
    }

    @Override
    public double getEffectiveCommissionRate(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        // If restaurant has custom commission, use it
        if (restaurant.getCommissionPercent() != null && restaurant.getCommissionPercent() > 0) {
            return restaurant.getCommissionPercent() / 100.0;
        }

        // Otherwise calculate based on order volume
        long orderCount = orderRepository.countByRestaurantIdAndCreatedAtAfter(
                restaurantId, LocalDateTime.now().minusMonths(1));

        return commissionTierProperties.getTiers().stream()
                .filter(tier -> orderCount >= tier.getMinOrders() && orderCount < tier.getMaxOrders())
                .findFirst()
                .map(tier -> tier.getCommissionPercent() / 100.0)
                .orElse(commissionTierProperties.getDefaultRate() / 100.0);
    }

    private String getTierName(long orderCount) {
        return commissionTierProperties.getTiers().stream()
                .filter(tier -> orderCount >= tier.getMinOrders() && orderCount < tier.getMaxOrders())
                .findFirst()
                .map(tier -> tier.getName())
                .orElse("Standard");
    }

    private double getMaxOrdersForTier(long orderCount) {
        return commissionTierProperties.getTiers().stream()
                .filter(tier -> orderCount >= tier.getMinOrders() && orderCount < tier.getMaxOrders())
                .findFirst()
                .map(tier -> tier.getMaxOrders())
                .orElse(Double.MAX_VALUE);
    }
}