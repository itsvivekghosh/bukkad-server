package com.bhukkad.delivery.api;

import com.bhukkad.delivery.domain.DeliveryZone;
import com.bhukkad.delivery.domain.DeliveryZoneRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public serviceability surface (permitAll hot path).
 *
 * <p>Zone data changes rarely but was queried with {@code findAll()} on every
 * request. A 30-second in-memory cache keeps the check at zero DB cost under
 * heavy traffic; inactive zones no longer count as serviceable.</p>
 */
@RestController
@RequestMapping("/api/v1/serviceability")
@RequiredArgsConstructor
public class ServiceabilityCheckController {

    private final DeliveryZoneRepository zoneRepository;

    private static final long CACHE_TTL_MILLIS = 30_000L;

    private final AtomicReference<CachedZones> cache = new AtomicReference<>();

    private record CachedZones(List<DeliveryZone> zones, long loadedAtMillis) {}

    @GetMapping("/zones")
    public List<DeliveryZone> zones() {
        return cachedZones();
    }

    @GetMapping("/check")
    public java.util.Map<String, Object> serviceability(
            @RequestParam(required = false) String zoneName,
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double subtotal) {
        // Legacy form: zone-name check keeps its boolean answer.
        if (zoneName != null && !zoneName.isBlank()) {
            boolean ok = cachedZones().stream()
                    .anyMatch(z -> z.getIsActive()
                            && z.getName() != null
                            && z.getName().equalsIgnoreCase(zoneName));
            return java.util.Map.of("serviceable", ok, "zone", zoneName);
        }
        // App form: restaurant + coordinates (+ optional cart subtotal).
        if (restaurantId == null || latitude == null || longitude == null) {
            throw new com.bhukkad.common.error.BusinessException(
                    "Provide zoneName, or restaurantId + latitude + longitude");
        }
        double lat = latitude;
        double lng = longitude;
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new com.bhukkad.common.error.BusinessException("Invalid coordinates");
        }
        // The zone radius check runs against the zone covering the origin
        // restaurant; with no per-restaurant geo join in the delivery DB the
        // dev build reports the aggregate zone state honestly: serviceable
        // only when at least one active zone exists.
        boolean anyActive = cachedZones().stream().anyMatch(DeliveryZone::getIsActive);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("serviceable", anyActive);
        body.put("restaurantId", restaurantId);
        body.put("latitude", lat);
        body.put("longitude", lng);
        body.put("deliveryFee", anyActive ? 30.0 : 0.0);
        body.put("etaMinutes", anyActive ? 35 : 0);
        return body;
    }

    private List<DeliveryZone> cachedZones() {
        CachedZones current = cache.get();
        long now = System.currentTimeMillis();
        if (current == null || now - current.loadedAtMillis() > CACHE_TTL_MILLIS) {
            CachedZones fresh = new CachedZones(zoneRepository.findAll(), now);
            // Last writer wins — acceptable for a read-through snapshot cache.
            cache.set(fresh);
            return fresh.zones();
        }
        return current.zones();
    }
}
