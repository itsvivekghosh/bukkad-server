package com.bhukkad.zone;

import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.entity.ZoneSurgeRule;
import com.bhukkad.repository.ZoneSurgeRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resolves effective surge multipliers using zone base surge, time-of-day rules
 * (V14) and a demand-forecast adjustment (historical hourly volume).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneSurgeService {

    private final ZoneSurgeRuleRepository zoneSurgeRuleRepository;
    private final DemandForecastService demandForecastService;

    /**
     * Computes the effective surge multiplier for a zone at the current time.
     *
     * <p>The rule-based multiplier (zone base + time-of-day rules) is combined
     * with the platform demand forecast so surge reflects predicted demand. The
     * forecast only adjusts upward or downward within a bounded range; it never
     * disables a configured rule.</p>
     */
    public double resolveEffectiveSurge(DeliveryZone zone) {
        double base = zone.getSurgeMultiplier() != null ? zone.getSurgeMultiplier() : 1.0;
        List<ZoneSurgeRule> rules = zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(zone.getId());
        double ruleMultiplier = base;
        if (!rules.isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            int hour = now.getHour();
            int dayOfWeek = now.getDayOfWeek().getValue();
            ruleMultiplier = rules.stream()
                    .filter(rule -> rule.getDayOfWeek() == null || rule.getDayOfWeek() == dayOfWeek)
                    .filter(rule -> hour >= rule.getStartHour() && hour < rule.getEndHour())
                    .mapToDouble(ZoneSurgeRule::getSurgeMultiplier)
                    .max()
                    .orElse(base);
        }
        double ruleSurge = Math.max(base, ruleMultiplier);

        double forecastAdjustment = demandForecastService.forecastSurgeAdjustment();
        // Round to 2 decimals to keep downstream pricing stable and readable.
        return Math.round(ruleSurge * forecastAdjustment * 100.0) / 100.0;
    }
}
