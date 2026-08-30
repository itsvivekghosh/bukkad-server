package com.bhukkad.security;

import com.bhukkad.entity.User;

/**
 * Authorization checks for live-stream (WebSocket/SSE) subscriptions.
 *
 * <p>Port owned by the security layer: {@code StompAuthChannelInterceptor}
 * sits below every business domain, so it must depend on this abstraction
 * rather than on the live module's service (dependency inversion — see
 * SegregationArchTest). The live module provides the implementation backed by
 * restaurant ownership and order-ownership checks.</p>
 */
public interface LiveSubscriptionAuthorizer {

    /** @return true when the user owns the given restaurant (kitchen stream) */
    boolean canSubscribeKitchen(User user, Long restaurantId);

    /** @return true when the agent id matches the authenticated rider */
    boolean canSubscribeRider(User user, Long agentId);

    /** @return true when the order exists and belongs to the given customer */
    boolean canSubscribeCustomer(User user, Long orderId);
}
