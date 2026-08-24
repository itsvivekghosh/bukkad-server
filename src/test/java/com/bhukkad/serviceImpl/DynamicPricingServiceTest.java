package com.bhukkad.serviceImpl;

import com.bhukkad.config.DynamicPricingProperties;
import com.bhukkad.dto.request.DynamicPricingRuleRequest;
import com.bhukkad.entity.DynamicPricingRule;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.DynamicPricingRuleRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DynamicPricingServiceTest {

    @Mock
    private DynamicPricingRuleRepository ruleRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @Mock
    private SecurityUtils securityUtils;

    @Mock
    private DynamicPricingProperties pricingProperties;

    @InjectMocks
    private DynamicPricingServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(pricingProperties.getMinPrice()).thenReturn(10.0);
    }

    @Test
    void createRule_notOwner_throws() {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(99L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setOwner(owner);

        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        request.setName("Happy Hour");
        request.setType(DynamicPricingRule.RuleType.HAPPY_HOUR);
        request.setStartTime(LocalTime.of(12, 0));
        request.setEndTime(LocalTime.of(14, 0));
        request.setDiscountPercent(20.0);

        assertThrows(BusinessException.class, () -> service.createRule(1L, request));
    }

    @Test
    void createRule_owner_success() {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(1L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        restaurant.setOwner(owner);

        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(ruleRepository.save(any(DynamicPricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        request.setName("Happy Hour");
        request.setType(DynamicPricingRule.RuleType.HAPPY_HOUR);
        request.setStartTime(LocalTime.of(12, 0));
        request.setEndTime(LocalTime.of(14, 0));
        request.setDiscountPercent(20.0);
        request.setPriority(1);

        var response = service.createRule(1L, request);

        assertEquals("Happy Hour", response.getName());
        assertEquals(DynamicPricingRule.RuleType.HAPPY_HOUR, response.getType());
        assertEquals(20.0, response.getDiscountPercent());
        verify(ruleRepository).save(any(DynamicPricingRule.class));
    }

    @Test
    void calculateDynamicPrice_noRules_returnsBasePrice() {
        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of());

        double price = service.calculateDynamicPrice(1L, 100.0, 100.0);

        assertEquals(100.0, price);
    }

    @Test
    void calculateDynamicPrice_discountApplied() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(20.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(0.0);
        rule.setMaxDiscountAmount(50.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        double price = service.calculateDynamicPrice(1L, 100.0, 100.0);

        assertEquals(80.0, price);
    }

    @Test
    void calculateDynamicPrice_surgeApplied() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(0.0);
        rule.setSurgePercent(15.0);
        rule.setMinOrderAmount(0.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        double price = service.calculateDynamicPrice(1L, 100.0, 100.0);

        assertEquals(115.0, price);
    }

    @Test
    void calculateDynamicPrice_minPriceEnforced() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(50.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(0.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        double price = service.calculateDynamicPrice(1L, 10.0, 10.0);

        assertEquals(10.0, price);
    }

    @Test
    void isHappyHourActive_noRules_returnsFalse() {
        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of());

        assertFalse(service.isHappyHourActive(1L, LocalTime.of(12, 0), 1));
    }

    @Test
    void isHappyHourActive_activeRule_returnsTrue() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(true);
        rule.setDayOfWeek(0);
        rule.setStartTime(LocalTime.of(12, 0));
        rule.setEndTime(LocalTime.of(14, 0));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertTrue(service.isHappyHourActive(1L, LocalTime.of(13, 0), 1));
    }

    @Test
    void isHappyHourActive_inactiveRule_returnsFalse() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(false);
        rule.setDayOfWeek(0);
        rule.setStartTime(LocalTime.of(12, 0));
        rule.setEndTime(LocalTime.of(14, 0));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertFalse(service.isHappyHourActive(1L, LocalTime.of(13, 0), 1));
    }

    @Test
    void updateRule_notFound_throws() {
        when(ruleRepository.findById(1L)).thenReturn(Optional.empty());

        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        assertThrows(ResourceNotFoundException.class, () -> service.updateRule(1L, request));
    }

    @Test
    void deleteRule_success() {
        DynamicPricingRule rule = new DynamicPricingRule();
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(1L);
        restaurant.setOwner(owner);
        rule.setRestaurant(restaurant);

        when(ruleRepository.findById(1L)).thenReturn(Optional.of(rule));
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        service.deleteRule(1L);

        verify(ruleRepository).delete(rule);
    }
}