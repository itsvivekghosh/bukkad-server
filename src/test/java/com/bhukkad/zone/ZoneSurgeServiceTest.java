package com.bhukkad.zone;

import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.entity.ZoneSurgeRule;
import com.bhukkad.repository.ZoneSurgeRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZoneSurgeServiceTest {

    @Mock
    private ZoneSurgeRuleRepository zoneSurgeRuleRepository;

    @InjectMocks
    private ZoneSurgeService service;

    private DeliveryZone zone;

    @BeforeEach
    void setUp() {
        zone = new DeliveryZone();
        zone.setId(1L);
        zone.setSurgeMultiplier(1.5);
    }

    @Test
    void resolveEffectiveSurge_returnsBase_whenNoRules() {
        when(zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(1L)).thenReturn(List.of());
        assertEquals(1.5, service.resolveEffectiveSurge(zone));
    }

    @Test
    void resolveEffectiveSurge_returnsBase_whenZoneHasNullSurge() {
        zone.setSurgeMultiplier(null);
        when(zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(1L)).thenReturn(List.of());
        assertEquals(1.0, service.resolveEffectiveSurge(zone));
    }

    @Test
    void resolveEffectiveSurge_appliesActiveRuleForCurrentTime() {
        ZoneSurgeRule rule = new ZoneSurgeRule();
        rule.setSurgeMultiplier(2.0);
        // Match today's day-of-week and any hour (0-23 covers now)
        rule.setDayOfWeek(null);
        rule.setStartHour(0);
        rule.setEndHour(24);
        when(zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(1L)).thenReturn(List.of(rule));

        double result = service.resolveEffectiveSurge(zone);
        assertEquals(2.0, result);
    }

    @Test
    void resolveEffectiveSurge_takesMaxOfApplicableRules() {
        ZoneSurgeRule low = new ZoneSurgeRule();
        low.setSurgeMultiplier(1.2);
        low.setDayOfWeek(null);
        low.setStartHour(0);
        low.setEndHour(24);

        ZoneSurgeRule high = new ZoneSurgeRule();
        high.setSurgeMultiplier(2.5);
        high.setDayOfWeek(null);
        high.setStartHour(0);
        high.setEndHour(24);

        when(zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(1L)).thenReturn(List.of(low, high));

        assertEquals(2.5, service.resolveEffectiveSurge(zone));
    }

    @Test
    void resolveEffectiveSurge_ignoresNonApplicableRules() {
        ZoneSurgeRule otherDay = new ZoneSurgeRule();
        otherDay.setSurgeMultiplier(3.0);
        otherDay.setDayOfWeek(java.time.LocalDate.now().getDayOfWeek().getValue() % 7 + 1);
        otherDay.setStartHour(0);
        otherDay.setEndHour(24);

        when(zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(1L)).thenReturn(List.of(otherDay));

        // Rule only applies on a different day of week -> falls back to base surge
        assertEquals(1.5, service.resolveEffectiveSurge(zone));
    }

    @Test
    void resolveEffectiveSurge_doesNotDropBelowBase() {
        ZoneSurgeRule belowBase = new ZoneSurgeRule();
        belowBase.setSurgeMultiplier(0.8);
        belowBase.setDayOfWeek(null);
        belowBase.setStartHour(0);
        belowBase.setEndHour(24);
        when(zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(1L)).thenReturn(List.of(belowBase));

        assertEquals(1.5, service.resolveEffectiveSurge(zone));
    }
}