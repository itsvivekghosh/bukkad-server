package com.bhukkad.serviceImpl;

import com.bhukkad.config.DynamicPricingProperties;
import com.bhukkad.dto.request.DynamicPricingRuleRequest;
import com.bhukkad.dto.response.DynamicPricingRuleResponse;
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

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicPricingServiceImplTest {

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

    private RestaurantOwner owner(Long id) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(id);
        return owner;
    }

    private Restaurant restaurant(Long id, Long ownerId) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setOwner(owner(ownerId));
        return restaurant;
    }

    private DynamicPricingRuleRequest fullRequest() {
        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        request.setName("Happy Hour");
        request.setType(DynamicPricingRule.RuleType.HAPPY_HOUR);
        request.setStartTime(LocalTime.of(12, 0));
        request.setEndTime(LocalTime.of(14, 0));
        request.setDayOfWeek(3);
        request.setDiscountPercent(20.0);
        request.setSurgePercent(5.0);
        request.setMinOrderAmount(50.0);
        request.setMaxDiscountAmount(30.0);
        request.setPriority(4);
        request.setActive(false);
        return request;
    }

    private DynamicPricingRule ruleWithRestaurant(Long restaurantId) {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setId(10L);
        rule.setRestaurant(restaurant(restaurantId, 1L));
        rule.setName("Rule");
        rule.setType(DynamicPricingRule.RuleType.SURGE);
        rule.setActive(true);
        rule.setStartTime(LocalTime.of(9, 0));
        rule.setEndTime(LocalTime.of(18, 0));
        rule.setDayOfWeek(0);
        rule.setDiscountPercent(10.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(0.0);
        rule.setMaxDiscountAmount(5.0);
        rule.setPriority(2);
        rule.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
        return rule;
    }

    // ---------- createRule ----------

    @Test
    void createRule_restaurantNotFound_throws() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.createRule(1L, fullRequest()));
    }

    @Test
    void createRule_notOwner_throws() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant(1L, 99L)));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.createRule(1L, fullRequest()));
    }

    @Test
    void createRule_success_mapsAllFields() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant(1L, 1L)));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(ruleRepository.save(any(DynamicPricingRule.class))).thenAnswer(inv -> {
            DynamicPricingRule rule = inv.getArgument(0);
            rule.setId(10L);
            rule.setCreatedAt(LocalDateTime.of(2026, 8, 1, 10, 0));
            return rule;
        });

        DynamicPricingRuleResponse response = service.createRule(1L, fullRequest());

        assertEquals(10L, response.getId());
        assertEquals(1L, response.getRestaurantId());
        assertEquals("Happy Hour", response.getName());
        assertEquals(DynamicPricingRule.RuleType.HAPPY_HOUR, response.getType());
        assertEquals(LocalTime.of(12, 0), response.getStartTime());
        assertEquals(LocalTime.of(14, 0), response.getEndTime());
        assertEquals(3, response.getDayOfWeek());
        assertEquals(20.0, response.getDiscountPercent());
        assertEquals(5.0, response.getSurgePercent());
        assertEquals(50.0, response.getMinOrderAmount());
        assertEquals(30.0, response.getMaxDiscountAmount());
        assertEquals(4, response.getPriority());
        assertEquals(false, response.getActive());
        assertEquals(LocalDateTime.of(2026, 8, 1, 10, 0), response.getCreatedAt());
        verify(ruleRepository).save(any(DynamicPricingRule.class));
    }

    @Test
    void createRule_nullFields_applyDefaults() {
        when(restaurantRepository.findById(1L)).thenReturn(Optional.of(restaurant(1L, 1L)));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(ruleRepository.save(any(DynamicPricingRule.class))).thenAnswer(inv -> {
            DynamicPricingRule rule = inv.getArgument(0);
            rule.setId(1L);
            return rule;
        });

        DynamicPricingRuleRequest request = new DynamicPricingRuleRequest();
        request.setName("Null Rule");
        request.setType(DynamicPricingRule.RuleType.WEEKEND_SPECIAL);
        request.setStartTime(LocalTime.of(10, 0));
        request.setEndTime(LocalTime.of(11, 0));
        request.setDayOfWeek(null);
        request.setDiscountPercent(null);
        request.setSurgePercent(null);
        request.setMinOrderAmount(null);
        request.setMaxDiscountAmount(null);
        request.setPriority(null);
        request.setActive(null);

        DynamicPricingRuleResponse response = service.createRule(1L, request);

        assertEquals(0, response.getDayOfWeek());
        assertEquals(0.0, response.getDiscountPercent());
        assertEquals(0.0, response.getSurgePercent());
        assertEquals(0.0, response.getMinOrderAmount());
        assertEquals(0.0, response.getMaxDiscountAmount());
        assertEquals(0, response.getPriority());
        assertEquals(true, response.getActive());
    }

    // ---------- updateRule ----------

    @Test
    void updateRule_notFound_throws() {
        when(ruleRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.updateRule(1L, fullRequest()));
    }

    @Test
    void updateRule_notOwner_throws() {
        DynamicPricingRule rule = ruleWithRestaurant(5L);
        rule.getRestaurant().setOwner(owner(77L));
        when(ruleRepository.findById(10L)).thenReturn(Optional.of(rule));
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 77L)));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.updateRule(10L, fullRequest()));
    }

    @Test
    void updateRule_success() {
        DynamicPricingRule rule = ruleWithRestaurant(5L);
        when(ruleRepository.findById(10L)).thenReturn(Optional.of(rule));
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 1L)));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(ruleRepository.save(any(DynamicPricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        DynamicPricingRuleResponse response = service.updateRule(10L, fullRequest());

        assertEquals("Happy Hour", response.getName());
        assertEquals(4, response.getPriority());
        verify(ruleRepository).save(rule);
    }

    // ---------- deleteRule ----------

    @Test
    void deleteRule_notFound_throws() {
        when(ruleRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.deleteRule(1L));
    }

    @Test
    void deleteRule_success() {
        DynamicPricingRule rule = ruleWithRestaurant(5L);
        when(ruleRepository.findById(10L)).thenReturn(Optional.of(rule));
        when(restaurantRepository.findById(5L)).thenReturn(Optional.of(restaurant(5L, 1L)));
        when(securityUtils.getCurrentUserId()).thenReturn(1L);

        service.deleteRule(10L);

        verify(ruleRepository).delete(rule);
    }

    // ---------- getRulesByRestaurant ----------

    @Test
    void getRulesByRestaurant_noRules_returnsEmptyList() {
        when(ruleRepository.findActiveByRestaurant(1L)).thenReturn(List.of());

        List<DynamicPricingRuleResponse> responses = service.getRulesByRestaurant(1L);

        assertEquals(0, responses.size());
    }

    @Test
    void getRulesByRestaurant_mapsRules() {
        when(ruleRepository.findActiveByRestaurant(1L))
                .thenReturn(List.of(ruleWithRestaurant(1L), ruleWithRestaurant(1L)));

        List<DynamicPricingRuleResponse> responses = service.getRulesByRestaurant(1L);

        assertEquals(2, responses.size());
        assertEquals("Rule", responses.get(0).getName());
        assertEquals(1L, responses.get(0).getRestaurantId());
        assertEquals(LocalDateTime.of(2026, 8, 1, 10, 0), responses.get(0).getCreatedAt());
    }

    // ---------- calculateDynamicPrice ----------

    @Test
    void calculateDynamicPrice_noActiveRules_returnsBasePriceUnclamped() {
        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of());

        assertEquals(5.0, service.calculateDynamicPrice(1L, 5.0, 10.0));
    }

    @Test
    void calculateDynamicPrice_noRuleMeetsSubtotal_returnsBasePrice() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(20.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(500.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        assertEquals(100.0, service.calculateDynamicPrice(1L, 100.0, 100.0));
    }

    @Test
    void calculateDynamicPrice_discountApplied() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(20.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(0.0);
        rule.setMaxDiscountAmount(0.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        assertEquals(80.0, service.calculateDynamicPrice(1L, 100.0, 100.0));
    }

    @Test
    void calculateDynamicPrice_discountCappedByMaxDiscountAmount() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(50.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(0.0);
        rule.setMaxDiscountAmount(30.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        assertEquals(70.0, service.calculateDynamicPrice(1L, 100.0, 100.0));
    }

    @Test
    void calculateDynamicPrice_surgeApplied() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(0.0);
        rule.setSurgePercent(15.0);
        rule.setMinOrderAmount(0.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        assertEquals(115.0, service.calculateDynamicPrice(1L, 100.0, 100.0));
    }

    @Test
    void calculateDynamicPrice_discountAndSurgeCombined() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(20.0);
        rule.setSurgePercent(10.0);
        rule.setMinOrderAmount(0.0);
        rule.setMaxDiscountAmount(0.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        assertEquals(88.0, service.calculateDynamicPrice(1L, 100.0, 100.0));
    }

    @Test
    void calculateDynamicPrice_minPriceEnforced() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setDiscountPercent(50.0);
        rule.setSurgePercent(0.0);
        rule.setMinOrderAmount(0.0);
        rule.setPriority(1);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(rule));

        assertEquals(10.0, service.calculateDynamicPrice(1L, 10.0, 10.0));
    }

    @Test
    void calculateDynamicPrice_highestPriorityRuleSelected() {
        DynamicPricingRule low = new DynamicPricingRule();
        low.setDiscountPercent(0.0);
        low.setSurgePercent(5.0);
        low.setMinOrderAmount(0.0);
        low.setPriority(1);

        DynamicPricingRule high = new DynamicPricingRule();
        high.setDiscountPercent(0.0);
        high.setSurgePercent(25.0);
        high.setMinOrderAmount(0.0);
        high.setPriority(9);

        when(ruleRepository.findActiveAtTime(eq(1L), any(LocalTime.class))).thenReturn(List.of(low, high));

        assertEquals(125.0, service.calculateDynamicPrice(1L, 100.0, 100.0));
    }

    // ---------- isHappyHourActive ----------

    @Test
    void isHappyHourActive_public_noRules_returnsFalse() {
        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of());

        assertFalse(service.isHappyHourActive(1L));
    }

    @Test
    void isHappyHourActive_public_activeRuleCoversAllDay_returnsTrue() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(true);
        rule.setDayOfWeek(0);
        rule.setStartTime(LocalTime.of(0, 0));
        rule.setEndTime(LocalTime.of(23, 59));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertTrue(service.isHappyHourActive(1L));
    }

    @Test
    void isHappyHourActive_noRules_returnsFalse() {
        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of());

        assertFalse(service.isHappyHourActive(1L, LocalTime.of(13, 0), 1));
    }

    @Test
    void isHappyHourActive_inactiveRule_returnsFalse() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(false);
        rule.setDayOfWeek(0);
        rule.setStartTime(LocalTime.of(0, 0));
        rule.setEndTime(LocalTime.of(23, 59));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertFalse(service.isHappyHourActive(1L, LocalTime.of(13, 0), 1));
    }

    @Test
    void isHappyHourActive_ruleForDifferentDay_returnsFalse() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(true);
        rule.setDayOfWeek(5);
        rule.setStartTime(LocalTime.of(0, 0));
        rule.setEndTime(LocalTime.of(23, 59));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertFalse(service.isHappyHourActive(1L, LocalTime.of(13, 0), 1));
    }

    @Test
    void isHappyHourActive_timeOutsideWindow_returnsFalse() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(true);
        rule.setDayOfWeek(0);
        rule.setStartTime(LocalTime.of(12, 0));
        rule.setEndTime(LocalTime.of(14, 0));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertFalse(service.isHappyHourActive(1L, LocalTime.of(10, 0), 1));
        assertFalse(service.isHappyHourActive(1L, LocalTime.of(15, 0), 1));
    }

    @Test
    void isHappyHourActive_matchingDayAndTime_returnsTrue() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(true);
        rule.setDayOfWeek(1);
        rule.setStartTime(LocalTime.of(12, 0));
        rule.setEndTime(LocalTime.of(14, 0));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertTrue(service.isHappyHourActive(1L, LocalTime.of(13, 0), 1));
    }

    @Test
    void isHappyHourActive_boundaryTimes_areInclusive() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setActive(true);
        rule.setDayOfWeek(0);
        rule.setStartTime(LocalTime.of(12, 0));
        rule.setEndTime(LocalTime.of(14, 0));

        when(ruleRepository.findByRestaurantAndType(1L, DynamicPricingRule.RuleType.HAPPY_HOUR))
                .thenReturn(List.of(rule));

        assertTrue(service.isHappyHourActive(1L, LocalTime.of(12, 0), 3));
        assertTrue(service.isHappyHourActive(1L, LocalTime.of(14, 0), 3));
    }

    @Test
    void verifyRestaurantOwnership_isNeverCalledForListOperation() {
        when(ruleRepository.findActiveByRestaurant(1L)).thenReturn(List.of());

        service.getRulesByRestaurant(1L);

        verify(restaurantRepository, never()).findById(any());
    }
}
