package com.bhukkad.live;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderLiveTopicsTest {

    @Test
    void kitchen_buildsKitchenTopic() {
        assertEquals("/topic/kitchen/42", OrderLiveTopics.kitchen(42L));
    }

    @Test
    void rider_buildsRiderTopic() {
        assertEquals("/topic/rider/7", OrderLiveTopics.rider(7L));
    }
}
