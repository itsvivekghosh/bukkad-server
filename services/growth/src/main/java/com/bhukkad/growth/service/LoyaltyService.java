package com.bhukkad.growth.service;

import com.bhukkad.growth.dto.LoyaltyPointsResponse;

public interface LoyaltyService {

    LoyaltyPointsResponse getLoyaltyPoints(Long customerId);

    void creditPoints(Long customerId, int points, String reason);

    boolean redeemPoints(Long customerId, int points);

    int calculatePointsForOrder(double orderAmount);
}
