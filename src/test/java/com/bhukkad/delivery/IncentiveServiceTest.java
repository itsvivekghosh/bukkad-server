package com.bhukkad.delivery;

import com.bhukkad.entity.RiderEarning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IncentiveServiceTest {

    private IncentiveService service;

    @BeforeEach
    void setUp() {
        service = new IncentiveService();
    }

    @Test
    void applyIncentives_noRules_returnsZeroBonus() {
        service.setRules(new HashMap<>());

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 5, true, true);

        assertEquals(0.0, result.bonusAmount());
        assertNull(result.bonusReason());
    }

    @Test
    void applyIncentives_nullRules_returnsZeroBonus() {
        service.setRules(null);

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 5, true, true);

        assertEquals(0.0, result.bonusAmount());
    }

    @Test
    void applyIncentives_emptyRulesWithEarning_resetsBonus() {
        service.setRules(new HashMap<>());
        RiderEarning earning = new RiderEarning();
        earning.setBonusAmount(50.0);
        earning.setBonusReason("old");

        service.applyIncentives(earning, 5, true, true);

        assertEquals(0.0, earning.getBonusAmount());
        assertNull(earning.getBonusReason());
    }

    @Test
    void applyIncentives_perDeliveryBonus_appliedForDeliveries() {
        service.setRules(Map.of(IncentiveService.RULE_PER_DELIVERY_BONUS, 5.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 3, false, false);

        assertEquals(15.0, result.bonusAmount());
        assertTrue(result.bonusReason().contains("per-delivery"));
    }

    @Test
    void applyIncentives_perDeliveryBonus_zeroDeliveries_skipped() {
        service.setRules(Map.of(IncentiveService.RULE_PER_DELIVERY_BONUS, 5.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 0, false, false);

        assertEquals(0.0, result.bonusAmount());
        assertNull(result.bonusReason());
    }

    @Test
    void applyIncentives_onTimeBonus_appliedWhenOnTime() {
        service.setRules(Map.of(IncentiveService.RULE_ON_TIME_BONUS, 10.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 0, true, false);

        assertEquals(10.0, result.bonusAmount());
        assertTrue(result.bonusReason().contains("on-time"));
    }

    @Test
    void applyIncentives_onTimeBonus_notOnTime_skipped() {
        service.setRules(Map.of(IncentiveService.RULE_ON_TIME_BONUS, 10.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 0, false, false);

        assertEquals(0.0, result.bonusAmount());
        assertNull(result.bonusReason());
    }

    @Test
    void applyIncentives_streakBonus_appliedAtThreshold() {
        service.setRules(Map.of(IncentiveService.RULE_STREAK_BONUS, 25.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 10, false, false);

        assertEquals(25.0, result.bonusAmount());
        assertTrue(result.bonusReason().contains("streak"));
    }

    @Test
    void applyIncentives_streakBonus_belowThreshold_skipped() {
        service.setRules(Map.of(IncentiveService.RULE_STREAK_BONUS, 25.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 9, false, false);

        assertEquals(0.0, result.bonusAmount());
        assertNull(result.bonusReason());
    }

    @Test
    void applyIncentives_peakMultiplier_appliedDuringPeakWithBonus() {
        service.setRules(Map.of(
                IncentiveService.RULE_PEAK_HOUR_MULTIPLIER, 2.0,
                IncentiveService.RULE_PER_DELIVERY_BONUS, 5.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 2, false, true);

        assertEquals(20.0, result.bonusAmount());
        assertTrue(result.bonusReason().contains("peak"));
    }

    @Test
    void applyIncentives_peakMultiplier_noBonus_skipped() {
        service.setRules(Map.of(IncentiveService.RULE_PEAK_HOUR_MULTIPLIER, 2.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 0, false, true);

        assertEquals(0.0, result.bonusAmount());
        assertNull(result.bonusReason());
    }

    @Test
    void applyIncentives_allBonusesCombined_appliedToEarning() {
        service.setRules(Map.of(
                IncentiveService.RULE_PER_DELIVERY_BONUS, 5.0,
                IncentiveService.RULE_ON_TIME_BONUS, 10.0,
                IncentiveService.RULE_STREAK_BONUS, 25.0,
                IncentiveService.RULE_PEAK_HOUR_MULTIPLIER, 1.5));

        RiderEarning earning = new RiderEarning();
        earning.setAmount(100.0);

        IncentiveService.IncentiveResult result = service.applyIncentives(earning, 10, true, true);

        // (5*10 + 10 + 25) * 1.5 = 85 * 1.5 = 127.5
        assertEquals(127.5, result.bonusAmount());
        assertEquals(127.5, earning.getBonusAmount());
        assertNotNull(earning.getBonusReason());
        assertTrue(earning.getBonusReason().contains("per-delivery"));
        assertTrue(earning.getBonusReason().contains("on-time"));
        assertTrue(earning.getBonusReason().contains("streak"));
        assertTrue(earning.getBonusReason().contains("peak"));
    }

    @Test
    void applyIncentives_unknownRuleKeys_ignored() {
        service.setRules(Map.of("unknown-rule", 100.0));

        IncentiveService.IncentiveResult result = service.applyIncentives(null, 5, true, true);

        assertEquals(0.0, result.bonusAmount());
        assertNull(result.bonusReason());
    }
}