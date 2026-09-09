package com.bhukkad.delivery.live;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replay buffer sizing defaults: bounded streams with a one-hour horizon.
 */
class OrderLiveReplayPropertiesTest {

    @Test
    void defaults_matchRolloutGuidance() {
        OrderLiveReplayProperties properties = new OrderLiveReplayProperties();

        assertThat(properties.getMaxEventsPerStream()).isEqualTo(200);
        assertThat(properties.getTtlSeconds()).isEqualTo(3600L);
    }

    @Test
    void setters_roundTrip() {
        OrderLiveReplayProperties properties = new OrderLiveReplayProperties();
        properties.setMaxEventsPerStream(50);
        properties.setTtlSeconds(120);

        assertThat(properties.getMaxEventsPerStream()).isEqualTo(50);
        assertThat(properties.getTtlSeconds()).isEqualTo(120L);
    }
}
