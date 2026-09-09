package com.bhukkad.delivery;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Delivery-truth (V14) defaults: ~36 km/h rider speed, 8-minute pickup buffer
 * and snapshot recording enabled out of the box.
 */
class DeliveryTruthPropertiesTest {

    @Test
    void defaults() {
        DeliveryTruthProperties properties = new DeliveryTruthProperties();

        assertThat(properties.getAvgSpeedKmPerMin()).isEqualTo(0.6);
        assertThat(properties.getPickupBufferMinutes()).isEqualTo(8);
        assertThat(properties.getConfidenceBandMinutes()).isEqualTo(5);
        assertThat(properties.isRecordSnapshots()).isTrue();
    }

    @Test
    void settersRoundTrip() {
        DeliveryTruthProperties properties = new DeliveryTruthProperties();
        properties.setAvgSpeedKmPerMin(0.5);
        properties.setPickupBufferMinutes(12);
        properties.setConfidenceBandMinutes(3);
        properties.setRecordSnapshots(false);

        assertThat(properties.getAvgSpeedKmPerMin()).isEqualTo(0.5);
        assertThat(properties.getPickupBufferMinutes()).isEqualTo(12);
        assertThat(properties.getConfidenceBandMinutes()).isEqualTo(3);
        assertThat(properties.isRecordSnapshots()).isFalse();
    }
}
