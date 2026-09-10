package com.bhukkad.growth.service;

import com.bhukkad.growth.dto.LoyaltyPointsResponse;

public interface LoyaltyService {

    LoyaltyPointsResponse getLoyaltyPoints(Long customerId);

    void creditPoints(Long customerId, int points, String reason);

    /**
     * One-transaction ledger credit (ADR-005): appends the CREDIT row and
     * applies the atomic balance upsert. {@code referenceId} (the caller's
     * idempotency key) makes a replay a no-op instead of a second grant.
     */
    void creditPoints(Long customerId, int points, String reason, String referenceId);

    boolean redeemPoints(Long customerId, int points);

    int calculatePointsForOrder(double orderAmount);
}
