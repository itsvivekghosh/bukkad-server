package com.bhukkad.order.api;

/**
 * Ownership checks against the order domain. Extracted as its own port because
 * live-streaming authorization needs a single boolean — shipping the full
 * summary across the boundary would leak more data than the check requires
 * (and, post-extraction, would cost a full fetch where an exists-query
 * suffices).
 */
public interface OrderOwnershipPort {

    /** @return true when the order exists and belongs to the given customer */
    boolean isOwnedByCustomer(Long orderId, Long customerId);
}
