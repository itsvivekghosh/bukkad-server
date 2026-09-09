package com.bhukkad.delivery.live;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * STOMP destination strings for the three live audiences.
 */
class OrderLiveTopicsTest {

    @Test
    void kitchenTopicUsesRestaurantPrefix() {
        assertThat(OrderLiveTopics.kitchen(7L)).isEqualTo("/topic/kitchen/7");
        assertThat(OrderLiveTopics.KITCHEN_PREFIX).isEqualTo("/topic/kitchen/");
    }

    @Test
    void riderTopicUsesAgentPrefix() {
        assertThat(OrderLiveTopics.rider(3L)).isEqualTo("/topic/rider/3");
        assertThat(OrderLiveTopics.RIDER_PREFIX).isEqualTo("/topic/rider/");
    }

    @Test
    void customerTopicUsesOrderPrefix() {
        assertThat(OrderLiveTopics.customer(42L)).isEqualTo("/topic/order/42");
        assertThat(OrderLiveTopics.CUSTOMER_PREFIX).isEqualTo("/topic/order/");
    }

    @Test
    void nullIdRendersNullSuffix() {
        assertThat(OrderLiveTopics.kitchen(null)).isEqualTo("/topic/kitchen/null");
    }
}
