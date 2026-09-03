package com.bhukkad.order.api;

import java.util.Optional;

/**
 * Read access to the order domain for other services. Cross-domain consumers
 * must depend on this interface (bean: {@code OrderApiAdapter}), not on the
 * order domain's repository or entities.
 */
public interface OrderQueryPort {

    /** @return the order summary, or empty when the order does not exist */
    Optional<OrderSummary> findSummary(Long orderId);

    /** @return the order summary
     * @throws com.bhukkad.common.error.ResourceNotFoundException when missing */
    OrderSummary requireSummary(Long orderId);
}
