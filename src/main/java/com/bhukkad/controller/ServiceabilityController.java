package com.bhukkad.controller;

import com.bhukkad.cache.ServiceabilityCacheService;
import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.ServiceabilityResponse;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.zone.DeliveryZoneService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public serviceability checks: zone coverage, distance, and estimated delivery fee.
 *
 * <p>Clients hit this endpoint repeatedly while the customer edits their cart or
 * moves the delivery pin, so the verdict is served through
 * {@link ServiceabilityCacheService} with a short TTL. The cache wraps the whole
 * request, including the restaurant load, because the zone computation needs the
 * restaurant's address.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/serviceability")
@RequiredArgsConstructor
public class ServiceabilityController {

    private final DeliveryZoneService deliveryZoneService;
    private final RestaurantRepository restaurantRepository;
    private final ServiceabilityCacheService serviceabilityCacheService;

    /**
     * Checks whether a restaurant can deliver to the given coordinates.
     *
     * <p>On a cache hit neither the restaurant nor its delivery zones are loaded.
     * On a miss the restaurant is loaded with its address and zones, the verdict
     * is computed and then cached against all four request parameters.
     *
     * @param restaurantId restaurant being ordered from
     * @param latitude     drop-off latitude
     * @param longitude    drop-off longitude
     * @param subtotal     current cart subtotal; affects fee slabs and free delivery
     * @return the serviceability verdict with zone, distance, fee and surge
     * @throws ResourceNotFoundException if the restaurant does not exist
     */
    @GetMapping("/check")
    public ResponseEntity<ApiResponse<ServiceabilityResponse>> checkServiceability(
            @RequestParam Long restaurantId,
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam(defaultValue = "0") double subtotal) {
        ServiceabilityResponse response = serviceabilityCacheService.getServiceability(
                restaurantId, latitude, longitude, subtotal,
                () -> {
                    Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId)
                            .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
                    return deliveryZoneService.isServiceable(restaurant, subtotal, latitude, longitude);
                });
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
