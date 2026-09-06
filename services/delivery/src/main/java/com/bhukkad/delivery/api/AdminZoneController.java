package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform-admin delivery-zone registry (monolith parity): list + create for
 * the ops console. Zone data itself remains the delivery domain's.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminZoneController {

    private final DeliveryZoneRepository zoneRepository;

    @GetMapping("/zones")
    @Transactional(readOnly = true)
    public List<DeliveryZone> zones() {
        return zoneRepository.findAll();
    }

    public record ZoneCreateRequest(String name, Double baseFee, Double radiusKm,
                                    Boolean isActive) {}

    @PostMapping("/zones")
    @Transactional
    public DeliveryZone createZone(@RequestBody(required = false) ZoneCreateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException("name is required");
        }
        DeliveryZone zone = new DeliveryZone();
        zone.setName(request.name().trim());
        zone.setIsActive(request.isActive() == null || request.isActive());
        return zoneRepository.save(zone);
    }
}
