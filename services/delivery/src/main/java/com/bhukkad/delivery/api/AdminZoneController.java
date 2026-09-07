package com.bhukkad.delivery.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.delivery.domain.CityConfig;
import com.bhukkad.delivery.domain.CityConfigRepository;
import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-admin delivery footprint (monolith parity): delivery zones and the
 * city registry — both live in the delivery domain. ADMIN-gated via method
 * security; the ops console reaches these through the gateway carve-out
 * {@code /api/v1/admin/{zones,cities}}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminZoneController {

    private final DeliveryZoneRepository zoneRepository;
    private final CityConfigRepository cityConfigRepository;

    // ------------------------------------------------------------------
    // Zones
    // ------------------------------------------------------------------

    @GetMapping("/zones")
    @Transactional(readOnly = true)
    public List<DeliveryZone> zones() {
        return zoneRepository.findAll();
    }

    public record ZoneUpsertRequest(String name, Double centerLatitude, Double centerLongitude,
                                    Double radiusKm, Integer estimatedDeliveryMinutes,
                                    Double deliveryFee, Boolean isActive) {}

    @PostMapping("/zones")
    @Transactional
    public DeliveryZone createZone(@RequestBody(required = false) ZoneUpsertRequest request) {
        DeliveryZone zone = new DeliveryZone();
        applyZone(zone, request);
        return zoneRepository.save(zone);
    }

    @PutMapping("/zones/{zoneId}")
    @Transactional
    public DeliveryZone updateZone(@PathVariable Long zoneId,
                                   @RequestBody(required = false) ZoneUpsertRequest request) {
        DeliveryZone zone = zoneRepository.findById(zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found: " + zoneId));
        applyZone(zone, request);
        return zoneRepository.save(zone);
    }

    @DeleteMapping("/zones/{zoneId}")
    @Transactional
    public Map<String, Object> deleteZone(@PathVariable Long zoneId) {
        DeliveryZone zone = zoneRepository.findById(zoneId)
                .orElseThrow(() -> new ResourceNotFoundException("Zone not found: " + zoneId));
        zoneRepository.delete(zone);
        return Map.of("message", "Zone deleted", "id", zoneId);
    }

    private void applyZone(DeliveryZone zone, ZoneUpsertRequest request) {
        if (request == null) {
            throw new BusinessException("Zone body is required");
        }
        if (request.name() != null && !request.name().isBlank()) {
            zone.setName(request.name().trim());
        } else if (zone.getName() == null) {
            throw new BusinessException("name is required");
        }
        if (request.isActive() != null) {
            zone.setIsActive(request.isActive());
        }
    }

    // ------------------------------------------------------------------
    // City registry
    // ------------------------------------------------------------------

    @GetMapping("/cities")
    @Transactional(readOnly = true)
    public List<CityConfig> cities() {
        return cityConfigRepository.findAll();
    }

    @PostMapping("/cities")
    @Transactional
    public Map<String, Object> createCity(@RequestBody(required = false) Map<String, Object> body) {
        String name = cityName(body);
        CityConfig city = new CityConfig();
        city.setCityName(name);
        cityConfigRepository.save(city);
        Map<String, Object> resp = cityBody(city);
        return resp;
    }

    @PutMapping("/cities/{cityId}")
    @Transactional
    public Map<String, Object> updateCity(@PathVariable Long cityId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        CityConfig city = cityConfigRepository.findById(cityId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
        city.setCityName(cityName(body));
        cityConfigRepository.save(city);
        return cityBody(city);
    }

    @DeleteMapping("/cities/{cityId}")
    @Transactional
    public Map<String, Object> deleteCity(@PathVariable Long cityId) {
        CityConfig city = cityConfigRepository.findById(cityId)
                .orElseThrow(() -> new ResourceNotFoundException("City not found: " + cityId));
        cityConfigRepository.delete(city);
        return Map.of("message", "City deleted", "id", cityId);
    }

    private static String cityName(Map<String, Object> body) {
        Object name = body == null ? null : body.get("city");
        if (name == null || String.valueOf(name).isBlank()) {
            throw new BusinessException("city is required");
        }
        return String.valueOf(name).trim();
    }

    private static Map<String, Object> cityBody(CityConfig city) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", city.getId());
        m.put("city", city.getCityName());
        m.put("displayName", city.getCityName());
        m.put("currency", city.getCurrency());
        m.put("timezone", city.getTimezone());
        m.put("isServiceable", true);
        m.put("isActive", true);
        return m;
    }

    // DefaultCityConfigFactory keeps entity creation compact for the dev build.
    static final class CityDefaults {
        private CityDefaults() {}

        static CityConfig newCity(String name) {
            CityConfig city = new CityConfig();
            city.setCityName(name);
            city.setCreatedAt(LocalDateTime.now());
            return city;
        }
    }
}
