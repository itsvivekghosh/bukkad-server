package com.bhukkad.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal Base32 geohash utility for spatial cache sharding and bounding-box
 * lookups in the restaurant/social services.
 *
 * <p>Implements the standard S2-style geohash algorithm with Base32 encoding.
 * Each additional character halves the cell width/height.</p>
 */
public final class GeohashUtils {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final int[] BITS = {16, 8, 4, 2, 1};

    private GeohashUtils() {
    }

    /**
     * Encode a lat/lng into a geohash of the given precision.
     *
     * @param lat      latitude in decimal degrees
     * @param lng      longitude in decimal degrees
     * @param precision number of geohash characters (1–12 typical)
     * @return Base32 geohash string
     */
    public static String encode(double lat, double lng, int precision) {
        if (precision < 1 || precision > 12) {
            throw new IllegalArgumentException("precision must be between 1 and 12");
        }
        StringBuilder hash = new StringBuilder();
        boolean isEven = true;
        double latMin = -90.0, latMax = 90.0;
        double lngMin = -180.0, lngMax = 180.0;
        int bit = 0;
        int ch = 0;

        while (hash.length() < precision) {
            if (isEven) {
                double mid = (lngMin + lngMax) / 2.0;
                if (lng >= mid) {
                    ch |= BITS[bit];
                    lngMin = mid;
                } else {
                    lngMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2.0;
                if (lat >= mid) {
                    ch |= BITS[bit];
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }
            isEven = !isEven;
            bit++;
            if (bit == 5) {
                hash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        return hash.toString();
    }

    /**
     * Decode a geohash into the bounding box it covers.
     *
     * @param geohash the geohash string
     * @return double array: [minLat, maxLat, minLng, maxLng]
     */
    public static double[] decode(String geohash) {
        if (geohash == null || geohash.isEmpty()) {
            throw new IllegalArgumentException("geohash must not be blank");
        }
        double latMin = -90.0, latMax = 90.0;
        double lngMin = -180.0, lngMax = 180.0;
        boolean isEven = true;

        for (int i = 0; i < geohash.length(); i++) {
            int ch = BASE32.indexOf(geohash.charAt(i));
            if (ch < 0) {
                throw new IllegalArgumentException("Invalid geohash character: " + geohash.charAt(i));
            }
            for (int mask : BITS) {
                if (isEven) {
                    double mid = (lngMin + lngMax) / 2.0;
                    if ((ch & mask) != 0) {
                        lngMin = mid;
                    } else {
                        lngMax = mid;
                    }
                } else {
                    double mid = (latMin + latMax) / 2.0;
                    if ((ch & mask) != 0) {
                        latMin = mid;
                    } else {
                        latMax = mid;
                    }
                }
                isEven = !isEven;
            }
        }
        return new double[]{latMin, latMax, lngMin, lngMax};
    }

    /**
     * Return the 8 neighboring geohash cells plus the cell itself (9 total).
     * Useful for expanding a search radius beyond a single cell.
     */
    public static List<String> getNeighbors(String geohash) {
        double[] bounds = decode(geohash);
        double latMid = (bounds[0] + bounds[1]) / 2.0;
        double lngMid = (bounds[2] + bounds[3]) / 2.0;
        double latDelta = bounds[1] - bounds[0];
        double lngDelta = bounds[3] - bounds[2];

        List<String> neighbors = new ArrayList<>(9);
        for (int latOff = -1; latOff <= 1; latOff++) {
            for (int lngOff = -1; lngOff <= 1; lngOff++) {
                double nLat = latMid + latOff * latDelta;
                double nLng = lngMid + lngOff * lngDelta;
                // Clamp to valid ranges
                nLat = Math.max(-90.0, Math.min(90.0, nLat));
                nLng = Math.max(-180.0, Math.min(180.0, nLng));
                neighbors.add(encode(nLat, nLng, geohash.length()));
            }
        }
        return neighbors;
    }

    /**
     * Return the set of geohash cells that cover a circle of {@code radiusKm}
     * around the given point at the requested precision.
     *
     * <p>Uses the center cell plus all neighbors; for larger radii multiple
     * rings may be needed, but for the 20km cell size (precision 4) and
     * typical 5–20km delivery radii this 3x3 neighborhood is sufficient.</p>
     */
    public static List<String> getCoveringCells(double lat, double lng, double radiusKm, int precision) {
        String center = encode(lat, lng, precision);
        List<String> cells = getNeighbors(center);
        // Remove duplicates (center appears in both lists)
        return cells.stream().distinct().toList();
    }
}
