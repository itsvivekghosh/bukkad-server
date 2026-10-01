package com.bhukkad.commerce.payment.domain.service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Read-only view of the order aggregate's authoritative total, owned by the
 * order module and consumed by payment.
 *
 * <p>Exists so {@link PaymentService} can re-price a charge against the amount
 * the SERVER computed for the order instead of trusting the amount in the
 * request body. Declared as a port (rather than injecting the order JPA
 * repository) to keep the payment → order dependency one-way and explicit.</p>
 */
public interface OrderAmountResolver {

    /**
     * @param orderId order the caller is trying to pay
     * @return the order's server-computed total, or empty when the order does
     *         not exist (the caller then decides how to fail)
     */
    Optional<BigDecimal> authoritativeTotal(Long orderId);
}