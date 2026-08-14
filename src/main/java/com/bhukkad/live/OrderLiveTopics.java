package com.bhukkad.live;

public final class OrderLiveTopics {

    public static final String KITCHEN_PREFIX = "/topic/kitchen/";
    public static final String RIDER_PREFIX = "/topic/rider/";
    public static final String CUSTOMER_PREFIX = "/topic/order/";

    private OrderLiveTopics() {}

    public static String kitchen(Long restaurantId) {
        return KITCHEN_PREFIX + restaurantId;
    }

    public static String rider(Long agentId) {
        return RIDER_PREFIX + agentId;
    }

    public static String customer(Long orderId) {
        return CUSTOMER_PREFIX + orderId;
    }
}
