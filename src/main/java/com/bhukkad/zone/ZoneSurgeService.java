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
 * Resolves effective surge multipliers using zone base surge and time-of-day rules (V14).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZoneSurgeService {

    private final ZoneSurgeRuleRepository zoneSurgeRuleRepository;

    /**
     * Computes the effective surge multiplier for a zone at the current time.
     */
    public double resolveEffectiveSurge(DeliveryZone zone) {
        double base = zone.getSurgeMultiplier() != null ? zone.getSurgeMultiplier() : 1.0;
        List<ZoneSurgeRule> rules = zoneSurgeRuleRepository.findByZoneIdAndIsActiveTrue(zone.getId());
        if (rules.isEmpty()) {
            return base;
        }
        LocalDateTime now = LocalDateTime.now();
        int hour = now.getHour();
        int dayOfWeek = now.getDayOfWeek().getValue();
        double ruleMultiplier = rules.stream()
                .filter(rule -> rule.getDayOfWeek() == null || rule.getDayOfWeek() == dayOfWeek)
                .filter(rule -> hour >= rule.getStartHour() && hour < rule.getEndHour())
                .mapToDouble(ZoneSurgeRule::getSurgeMultiplier)
                .max()
                .orElse(1.0);
        return Math.max(base, ruleMultiplier);
    }
}
