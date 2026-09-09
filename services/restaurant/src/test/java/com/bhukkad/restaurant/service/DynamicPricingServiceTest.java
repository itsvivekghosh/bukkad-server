package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.DynamicPricingRule;
import com.bhukkad.restaurant.domain.DynamicPricingRuleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Surge/promotional pricing rules: create, query active, deactivate.
 */
@ExtendWith(MockitoExtension.class)
class DynamicPricingServiceTest {

    @Mock private DynamicPricingRuleRepository ruleRepository;
    @InjectMocks private DynamicPricingService service;

    @Test
    void create_savesActiveRule() {
        when(ruleRepository.save(any(DynamicPricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        DynamicPricingRule rule = service.create(3L, "lunch-surge",
                new BigDecimal("1.5"), LocalTime.of(12, 0), LocalTime.of(14, 0));

        assertThat(rule.getRestaurantId()).isEqualTo(3L);
        assertThat(rule.getRuleName()).isEqualTo("lunch-surge");
        assertThat(rule.getMultiplier()).isEqualByComparingTo("1.5");
        assertThat(rule.getStartTime()).isEqualTo(LocalTime.of(12, 0));
        assertThat(rule.getEndTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(rule.getActive()).isTrue();
    }

    @Test
    void create_nonPositiveMultiplier_throws() {
        assertThatThrownBy(() -> service.create(3L, "bad", BigDecimal.ZERO, LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> service.create(3L, "bad", new BigDecimal("-1"), LocalTime.of(9, 0), LocalTime.of(10, 0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void active_returnsRulesForRestaurant() {
        when(ruleRepository.findByRestaurantIdAndActiveTrue(3L)).thenReturn(List.of(new DynamicPricingRule()));

        assertThat(service.active(3L)).hasSize(1);
    }

    @Test
    void deactivate_flipsFlag() {
        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setId(7L);
        rule.setActive(true);
        when(ruleRepository.findById(7L)).thenReturn(Optional.of(rule));
        when(ruleRepository.save(any(DynamicPricingRule.class))).thenAnswer(inv -> inv.getArgument(0));

        service.deactivate(7L);

        assertThat(rule.getActive()).isFalse();
        verify(ruleRepository).save(rule);
    }

    @Test
    void deactivate_unknown_throws() {
        when(ruleRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivate(99L)).isInstanceOf(Exception.class);
    }
}
