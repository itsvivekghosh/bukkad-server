package com.bhukkad.delivery.service;

import com.bhukkad.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ETA estimation: distance via the injected calculator plus fixed prep time,
 * ceiling-converted to minutes.
 */
class EtaServiceTest {

    private final DistanceCalculator distance = (fLat, fLng, tLat, tLng) -> 5.0;

    @Test
    void etaMinutes_usesDistancePlusPrepCeiling() {
        EtaService eta = new EtaService((fLat, fLng, tLat, tLng) -> 5.0);
        // 5 km at 20 km/h = 0.25 h = 15 min travel + 15 min prep = 30 min
        assertThat(eta.etaMinutes(12.9, 77.5, 12.97, 77.6)).isEqualTo(30);
    }

    @Test
    void etaMinutes_ceilFractionsUp() {
        // 1 km -> 3 min travel + 15 prep = 18 exactly; 0.1 km -> 0.3 min -> 1 min + 15 = 16
        EtaService eta = new EtaService((fLat, fLng, tLat, tLng) -> 0.1);
        assertThat(eta.etaMinutes(1, 1, 1.001, 1.001)).isEqualTo(16);
    }

    @Test
    void etaMinutes_zeroDistance_stillIncludesPrep() {
        EtaService eta = new EtaService((fLat, fLng, tLat, tLng) -> 0.0);
        assertThat(eta.etaMinutes(1, 1, 1, 1)).isEqualTo(15);
    }

    @Test
    void etaMinutes_negativeDistance_throws() {
        EtaService eta = new EtaService((fLat, fLng, tLat, tLng) -> -1.0);
        assertThatThrownBy(() -> eta.etaMinutes(1, 1, 1, 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("non-negative");
    }

    @Test
    void etaMinutes_longDistance_scales() {
        // 20 km -> 60 min travel + 15 prep = 75
        EtaService eta = new EtaService((fLat, fLng, tLat, tLng) -> 20.0);
        assertThat(eta.etaMinutes(1, 1, 1.5, 1.5)).isEqualTo(75);
    }
}
