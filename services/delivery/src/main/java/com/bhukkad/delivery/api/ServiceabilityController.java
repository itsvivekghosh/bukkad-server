package com.bhukkad.delivery.api;

import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import com.bhukkad.delivery.domain.ZoneSurgeRule;
import com.bhukkad.delivery.domain.ZoneSurgeRuleRepository;
import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.scan.AllowFullScan;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Serviceability + zone surge endpoints (port of monolith
 * {@code ServiceabilityController} + {@code DeliveryZoneAdminService}).
 */
@RestController
@RequestMapping("/api/v1/zones")
@RequiredArgsConstructor
public class ServiceabilityController {

    private final DeliveryZoneRepository zoneRepository;
    private final ZoneSurgeRuleRepository surgeRepository;

    @PostMapping
    public DeliveryZone createZone(@RequestParam String name) {
        DeliveryZone zone = new DeliveryZone();
        zone.setName(name);
        return zoneRepository.save(zone);
    }

    @GetMapping
    @AllowFullScan(reason = "G-6 reviewed: delivery zones are a small bounded reference table (single-digit rows, admin-managed)")
    public List<DeliveryZone> zones() {
        return zoneRepository.findAll();
    }

    @PostMapping("/{zoneId}/surge")
    public ZoneSurgeRule addSurge(@PathVariable Long zoneId,
                                  @RequestParam java.time.LocalTime startTime,
                                  @RequestParam java.time.LocalTime endTime,
                                  @RequestParam java.math.BigDecimal multiplier) {
        if (multiplier.signum() <= 0) {
            throw new BusinessException("Surge multiplier must be positive");
        }
        ZoneSurgeRule rule = new ZoneSurgeRule();
        rule.setZoneId(zoneId);
        rule.setStartTime(startTime);
        rule.setEndTime(endTime);
        rule.setMultiplier(multiplier);
        return surgeRepository.save(rule);
    }

    @GetMapping("/{zoneId}/surge")
    public List<ZoneSurgeRule> activeSurge(@PathVariable Long zoneId) {
        return surgeRepository.findByZoneIdAndActiveTrue(zoneId);
    }
}