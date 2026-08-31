package com.bhukkad.delivery.service;

/**
 * Distance calculator abstraction (port of monolith {@code RoadDistanceService}
 * contract). The default implementation is geodesic Haversine; a routing API
 * adapter can replace it.
 */
public interface DistanceCalculator {

    double distanceKm(double fromLat, double fromLng, double toLat, double toLng);
}