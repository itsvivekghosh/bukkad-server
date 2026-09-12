package com.bhukkad.delivery.service;

import com.bhukkad.delivery.RoadDistanceService;
import lombok.RequiredArgsConstructor;

/**
 * {@link DistanceCalculator} backed by {@link RoadDistanceService} — the one
 * sanctioned production caller of the OSRM stack (ADR-003 "wire or delete":
 * wire, behind gates). When {@code app.road-distance.enabled=false} (default)
 * RoadDistanceService returns its internal haversine fallback, so route()
 * output is numerically identical to {@link HaversineDistanceCalculator};
 * enabling road-distance flips the ETA input to real road km/durations
 * fetched via OsrmClient on the RestTemplate pool from OsrmHttpConfig
 * (connect/read timeouts from RoadDistanceProperties).
 *
 * <p>Registered as a bean only when {@code app.delivery.eta.enabled=true}
 * (see {@code DeliveryEtaConfig}) — deliberately not a {@code @Component}.</p>
 */
@RequiredArgsConstructor
public class RoadNetworkDistanceCalculator implements DistanceCalculator {

    private final RoadDistanceService roadDistanceService;

    @Override
    public double distanceKm(double fromLat, double fromLng, double toLat, double toLng) {
        return roadDistanceService.route(fromLat, fromLng, toLat, toLng).distanceKm();
    }
}
