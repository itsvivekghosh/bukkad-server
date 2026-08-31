package com.bhukkad.restaurant.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {
    Optional<CouponUsage> findByCouponIdAndCustomerIdAndOrderId(Long couponId, Long customerId, Long orderId);
    long countByCouponIdAndCustomerId(Long couponId, Long customerId);
}