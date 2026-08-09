package com.bhukkad.util;

public class DistanceCalculator {

    private static final int EARTH_RADIUS_KM = 6371;

    /**
     * Calculate distance between two coordinates using Haversine formula
     * @param lat1 Latitude of point 1
     * @param lon1 Longitude of point 1
     * @param lat2 Latitude of point 2
     * @param lon2 Longitude of point 2
     * @return Distance in kilometers
     */
    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_KM * c;
    }

    /**
     * Calculate estimated delivery time based on distance
     * @param distanceInKm Distance in kilometers
     * @return Estimated time in minutes
     */
    public static int calculateDeliveryTime(double distanceInKm) {
        // Assuming average speed of 20 km/h
        int baseTime = (int) Math.ceil((distanceInKm / 20) * 60);

        // Add 10 minutes for preparation and pickup
        return baseTime + 10;
    }

    /**
     * Calculate delivery fee based on distance
     * @param distanceInKm Distance in kilometers
     * @return Delivery fee
     */
    public static double calculateDeliveryFee(double distanceInKm) {
        if (distanceInKm <= 2) {
            return 20.0;
        } else if (distanceInKm <= 5) {
            return 40.0;
        } else if (distanceInKm <= 8) {
            return 60.0;
        } else {
            return 80.0;
        }
    }

    /**
     * Check if delivery is possible based on distance
     * @param distanceInKm Distance in kilometers
     * @return true if delivery is possible
     */
    public static boolean isDeliveryPossible(double distanceInKm) {
        return distanceInKm <= Constants.MAX_DELIVERY_DISTANCE_KM;
    }

    private DistanceCalculator() {
        // Private constructor to prevent instantiation
    }
}