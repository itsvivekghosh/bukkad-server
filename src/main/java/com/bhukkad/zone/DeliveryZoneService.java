package com.bhukkad.zone;

import com.bhukkad.dto.response.ServiceabilityResponse;
import com.bhukkad.entity.DeliveryZone;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.DeliveryZoneRepository;
import com.bhukkad.util.DistanceCalculator;
import com.bhukkad.util.PriceCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.Optional;

/**
 * Resolves delivery zones and computes zone-aware delivery fees using haversine distance.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeliveryZoneService {

    private final DeliveryZoneRepository deliveryZoneRepository;
    private final ZoneSurgeService zoneSurgeService;

    /**
     * Finds the nearest active delivery zone that contains the given coordinates.
     *
     * @param lat delivery latitude
     * @param lng delivery longitude
     * @return matching zone, if any
     */
    public Optional<DeliveryZone> findZoneForCoordinates(double lat, double lng) {
        return deliveryZoneRepository.findByIsActiveTrue().stream()
                .filter(zone -> distanceToZoneCenter(zone, lat, lng) <= zone.getRadiusKm())
                .min(Comparator.comparingDouble(zone -> distanceToZoneCenter(zone, lat, lng)));
    }

    /**
     * Calculates the delivery fee for an order using zone pricing and restaurant-to-delivery distance.
     *
     * @param restaurant     source restaurant
     * @param subtotal       order subtotal (reserved for future tiered pricing)
     * @param deliveryLat    delivery latitude
     * @param deliveryLng    delivery longitude
     * @return estimated delivery fee, rounded to two decimals
     */
    public double calculateDeliveryFee(Restaurant restaurant, double subtotal,
                                       double deliveryLat, double deliveryLng) {
        double restaurantLat = restaurant.getAddress().getLatitude();
        double restaurantLng = restaurant.getAddress().getLongitude();
        double distanceKm = DistanceCalculator.calculateDistance(
                restaurantLat, restaurantLng, deliveryLat, deliveryLng);

        Optional<DeliveryZone> zoneOpt = findZoneForCoordinates(deliveryLat, deliveryLng);
        if (zoneOpt.isEmpty()) {
            return PriceCalculator.roundToTwoDecimals(DistanceCalculator.calculateDeliveryFee(distanceKm));
        }

        DeliveryZone zone = zoneOpt.get();
        if (zone.getFreeDeliveryAbove() != null && subtotal >= zone.getFreeDeliveryAbove()) {
            return 0.0;
        }
        double effectiveSurge = zoneSurgeService.resolveEffectiveSurge(zone);
        double baseFee = zone.getBaseDeliveryFee() + (zone.getPerKmFee() * distanceKm);
        double fee = baseFee * effectiveSurge;
        return PriceCalculator.roundToTwoDecimals(fee);
    }

    /**
     * Checks whether the given coordinates are serviceable and returns zone and fee details.
     *
     * @param restaurant  source restaurant
     * @param subtotal    order subtotal
     * @param deliveryLat delivery latitude
     * @param deliveryLng delivery longitude
     * @return serviceability summary including zone and estimated fee
     */
    public ServiceabilityResponse isServiceable(Restaurant restaurant, double subtotal,
                                              double deliveryLat, double deliveryLng) {
        double restaurantLat = restaurant.getAddress().getLatitude();
        double restaurantLng = restaurant.getAddress().getLongitude();
        double distanceKm = PriceCalculator.roundToTwoDecimals(DistanceCalculator.calculateDistance(
                restaurantLat, restaurantLng, deliveryLat, deliveryLng));

        Optional<DeliveryZone> zoneOpt = findZoneForCoordinates(deliveryLat, deliveryLng);
        if (zoneOpt.isEmpty() || !DistanceCalculator.isDeliveryPossible(distanceKm)) {
            return ServiceabilityResponse.builder()
                    .serviceable(false)
                    .distanceKm(distanceKm)
                    .build();
        }

        DeliveryZone zone = zoneOpt.get();
        double fee = calculateDeliveryFee(restaurant, subtotal, deliveryLat, deliveryLng);
        double effectiveSurge = zoneSurgeService.resolveEffectiveSurge(zone);
        return ServiceabilityResponse.builder()
                .serviceable(true)
                .zoneId(zone.getId())
                .zoneName(zone.getName())
                .estimatedDeliveryFee(fee)
                .distanceKm(distanceKm)
                .surgeMultiplier(effectiveSurge)
                .build();
    }

    private double distanceToZoneCenter(DeliveryZone zone, double lat, double lng) {
        return DistanceCalculator.calculateDistance(
                zone.getCenterLatitude(), zone.getCenterLongitude(), lat, lng);
    }
}
