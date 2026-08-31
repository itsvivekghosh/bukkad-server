package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;

/**
 * ETA estimation (port of monolith {@code OrderEtaService}): distance + fixed
 * prep/restaurant handling time, converted to minutes.
 */
@RequiredArgsConstructor
public class EtaService {

    /** Average delivery speed, km/h, urban. */
    private static final double AVG_SPEED_KMH = 20.0;
    private static final double PREP_MINUTES = 15.0;

    private final DistanceCalculator distanceCalculator;

    public int etaMinutes(double fromLat, double fromLng, double toLat, double toLng) {
        double distance = distanceCalculator.distanceKm(fromLat, fromLng, toLat, toLng);
        if (distance < 0) throw new BusinessException("Distance must be non-negative");
        double travelHours = distance / AVG_SPEED_KMH;
        return (int) Math.ceil(travelHours * 60 + PREP_MINUTES);
    }
}