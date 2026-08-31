package com.bhukkad.delivery.api;

import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Delivery serviceability check (port of monolith {@code ServiceabilityController}
 * public surface): returns the zones serving a given area (simplified to zone
 * lookup by name in this module).
 */
@RestController
@RequestMapping("/api/v1/serviceability")
@RequiredArgsConstructor
public class ServiceabilityCheckController {

    private final DeliveryZoneRepository zoneRepository;

    @GetMapping("/zones")
    public List<DeliveryZone> zones() {
        return zoneRepository.findAll();
    }

    @GetMapping("/check")
    public boolean isServiceable(@RequestParam String zoneName) {
        return zoneRepository.findAll().stream()
                .anyMatch(z -> z.getName().equalsIgnoreCase(zoneName));
    }
}