package com.bhukkad.delivery.api;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Null-object ETA port: services without a live ETA source return empty.
 */
class DefaultEtaPortTest {

    @Test
    void computeEta_isAlwaysEmpty() {
        Optional<EtaPort.EtaSnapshot> eta = new DefaultEtaPort().computeEta(42L);

        assertThat(eta).isEmpty();
    }

    @Test
    void computeEta_emptyForUnknownOrder() {
        assertThat(new DefaultEtaPort().computeEta(null)).isEmpty();
    }
}
