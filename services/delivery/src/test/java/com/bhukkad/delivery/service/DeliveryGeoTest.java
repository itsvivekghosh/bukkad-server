package com.bhukkad.delivery.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryGeoTest {

    private final HaversineDistanceCalculator haversine = new HaversineDistanceCalculator();

    @Test
    void distance_zeroForSamePoint() {
        assertThat(haversine.distanceKm(19.076, 72.877, 19.076, 72.877)).isZero();
    }

    @Test
    void distance_mumbaiToPune_roughly150km() {
        double d = haversine.distanceKm(19.076, 72.877, 18.5204, 73.8567);
        assertThat(d).isBetween(100.0, 180.0);
    }

    @Test
    void distance_antipodal_maxCircaEarthHalf() {
        double d = haversine.distanceKm(0, 0, 0, 180);
        assertThat(d).isBetween(19900.0, 20100.0);
    }

    @Test
    void eta_usesDistanceAndPrep() {
        EtaService eta = new EtaService(haversine);
        // ~1 km at 20 km/h => 3 min travel + 15 min prep = ~18
        int minutes = eta.etaMinutes(19.076, 72.877, 19.085, 72.890);
        assertThat(minutes).isBetween(15, 25);
    }

    @Test
    void eta_distantOrder_longer() {
        EtaService eta = new EtaService(haversine);
        int near = eta.etaMinutes(19.076, 72.877, 19.077, 72.878);
        int far = eta.etaMinutes(19.076, 72.877, 18.5204, 73.8567);
        assertThat(far).isGreaterThan(near);
    }

    @Test
    void eta_rejectsNegativeDistance() {
        // No real negative distance; guard sanity by validating inputs upstream.
        EtaService eta = new EtaService((a, b, c, d) -> -1);
        assertThatThrownBy(() -> eta.etaMinutes(0, 0, 0, 0))
                .isInstanceOf(com.bhukkad.common.error.BusinessException.class);
    }
}
