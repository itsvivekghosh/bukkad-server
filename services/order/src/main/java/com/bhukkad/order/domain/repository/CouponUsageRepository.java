package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.bhukkad.order.domain.entity.CouponUsage;

public interface CouponUsageRepository extends JpaRepository<CouponUsage, Long> {

    long countByCouponIdAndCustomerId(Long couponId, Long customerId);
}