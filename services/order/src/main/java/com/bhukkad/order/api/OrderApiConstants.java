package com.bhukkad.order.api;
import com.bhukkad.order.domain.entity.Order;

/**
 * Order-status vocabulary shared across domains. Cross-domain code must use
 * these constants instead of importing {@code com.bhukkad.order.domain.entity.Order} for
 * its enum.
 */
public final class OrderApiConstants {

    public static final String STATUS_PLACED = "PLACED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_PREPARING = "PREPARING";
    public static final String STATUS_OUT_FOR_DELIVERY = "OUT_FOR_DELIVERY";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private OrderApiConstants() {
    }
}
