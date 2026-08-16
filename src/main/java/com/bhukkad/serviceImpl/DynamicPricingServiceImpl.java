package com.bhukkad.serviceImpl;

import com.bhukkad.config.DynamicPricingProperties;
import com.bhukkad.dto.request.DynamicPricingRuleRequest;
import com.bhukkad.dto.response.DynamicPricingRuleResponse;
import com.bhukkad.entity.DynamicPricingRule;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DynamicPricingRuleRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.DynamicPricingService;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DynamicPricingServiceImpl implements DynamicPricingService {

    private final DynamicPricingRuleRepository dynamicPricingRuleRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;
    private final DynamicPricingProperties pricingProperties;

    @Override
    @Transactional
    public DynamicPricingRuleResponse createRule(Long restaurantId, DynamicPricingRuleRequest request) {
        verifyRestaurantOwnership(restaurantId);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(restaurantId);

        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setRestaurant(restaurant);
        mapRequestToRule(request, rule);

        return toResponse(dynamicPricingRuleRepository.save(rule));
    }

    @Override
    @Transactional
    public DynamicPricingRuleResponse updateRule(Long ruleId, DynamicPricingRuleRequest request) {
        DynamicPricingRule rule = dynamicPricingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found"));

        verifyRestaurantOwnership(rule.getRestaurant().getId());
        mapRequestToRule(request, rule);

        return toResponse(dynamicPricingRuleRepository.save(rule));
    }

    @Override
    @Transactional
    public void deleteRule(Long ruleId) {
        DynamicPricingRule rule = dynamicPricingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found"));

        verifyRestaurantOwnership(rule.getRestaurant().getId());
        dynamicPricingRuleRepository.delete(rule);
    }

    @Override
    public List<DynamicPricingRuleResponse> getRulesByRestaurant(Long restaurantId) {
        return dynamicPricingRuleRepository.findActiveByRestaurant(restaurantId).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public double calculateDynamicPrice(Long restaurantId, double basePrice, double subtotal) {
        List<DynamicPricingRule> activeRules = dynamicPricingRuleRepository.findActiveAtTime(
                restaurantId, LocalTime.now());

        if (activeRules.isEmpty()) {
            return basePrice;
        }

        // Apply highest priority rule
        DynamicPricingRule bestRule = activeRules.stream()
                .filter(rule -> subtotal >= rule.getMinOrderAmount())
                .max((r1, r2) -> Integer.compare(r1.getPriority(), r2.getPriority()))
                .orElse(null);

        if (bestRule == null) {
            return basePrice;
        }

        double adjustedPrice = basePrice;

        // Apply discount
        if (bestRule.getDiscountPercent() > 0) {
            double discount = PriceCalculator.roundToTwoDecimals(
                    basePrice * (bestRule.getDiscountPercent() / 100.0));
            if (bestRule.getMaxDiscountAmount() > 0) {
                discount = Math.min(discount, bestRule.getMaxDiscountAmount());
            }
            adjustedPrice = PriceCalculator.roundToTwoDecimals(basePrice - discount);
        }

        // Apply surge
        if (bestRule.getSurgePercent() > 0) {
            double surge = PriceCalculator.roundToTwoDecimals(
                    adjustedPrice * (bestRule.getSurgePercent() / 100.0));
            adjustedPrice = PriceCalculator.roundToTwoDecimals(adjustedPrice + surge);
        }

        return Math.max(adjustedPrice, pricingProperties.getMinPrice());
    }

    @Override
    public boolean isHappyHourActive(Long restaurantId) {
        return isHappyHourActive(restaurantId, LocalTime.now(), LocalDate.now().getDayOfWeek().getValue());
    }

    boolean isHappyHourActive(Long restaurantId, LocalTime now, int today) {
        List<DynamicPricingRule> rules = dynamicPricingRuleRepository.findByRestaurantAndType(
                restaurantId, DynamicPricingRule.RuleType.HAPPY_HOUR);

        return rules.stream().anyMatch(rule -> {
            if (!Boolean.TRUE.equals(rule.getActive())) return false;
            if (rule.getDayOfWeek() != 0 && rule.getDayOfWeek() != today) return false;
            return !now.isBefore(rule.getStartTime()) && !now.isAfter(rule.getEndTime());
        });
    }

    private void verifyRestaurantOwnership(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));

        if (!restaurant.getOwner().getId().equals(securityUtils.getCurrentUserId())) {
            throw new BusinessException("Not your restaurant");
        }
    }

    private void mapRequestToRule(DynamicPricingRuleRequest request, DynamicPricingRule rule) {
        rule.setName(request.getName());
        rule.setType(request.getType());
        rule.setStartTime(request.getStartTime());
        rule.setEndTime(request.getEndTime());
        rule.setDayOfWeek(request.getDayOfWeek() != null ? request.getDayOfWeek() : 0);
        rule.setDiscountPercent(request.getDiscountPercent() != null ? request.getDiscountPercent() : 0.0);
        rule.setSurgePercent(request.getSurgePercent() != null ? request.getSurgePercent() : 0.0);
        rule.setMinOrderAmount(request.getMinOrderAmount() != null ? request.getMinOrderAmount() : 0.0);
        rule.setMaxDiscountAmount(request.getMaxDiscountAmount() != null ? request.getMaxDiscountAmount() : 0.0);
        rule.setPriority(request.getPriority() != null ? request.getPriority() : 0);
        rule.setActive(request.getActive() != null ? request.getActive() : true);
    }

    private DynamicPricingRuleResponse toResponse(DynamicPricingRule rule) {
        return DynamicPricingRuleResponse.builder()
                .id(rule.getId())
                .restaurantId(rule.getRestaurant().getId())
                .name(rule.getName())
                .type(rule.getType())
                .active(rule.getActive())
                .startTime(rule.getStartTime())
                .endTime(rule.getEndTime())
                .dayOfWeek(rule.getDayOfWeek())
                .discountPercent(rule.getDiscountPercent())
                .surgePercent(rule.getSurgePercent())
                .minOrderAmount(rule.getMinOrderAmount())
                .maxDiscountAmount(rule.getMaxDiscountAmount())
                .priority(rule.getPriority())
                .createdAt(rule.getCreatedAt())
                .build();
    }
}