package com.bhukkad.order.domain.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.bhukkad.order.domain.entity.Coupon;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    @Query("SELECT c FROM Coupon c WHERE c.active = true AND c.validFrom <= :now AND c.validUntil >= :now "
            + "AND (c.restaurantId = :restaurantId OR c.restaurantId IS NULL)")
    List<Coupon> findActiveCouponsForRestaurant(@Param("restaurantId") Long restaurantId,
                                                @Param("now") LocalDateTime now);

    @Query("SELECT c FROM Coupon c WHERE c.active = true AND c.validFrom <= :now AND c.validUntil >= :now "
            + "AND c.restaurantId IS NULL")
    List<Coupon> findActivePlatformCoupons(@Param("now") LocalDateTime now);

    /**
     * Atomically increments the coupon's used count only while it is still
     * within its usage limit, so concurrent checkouts cannot exceed the limit
     * or lose increments.
     *
     * @return 1 when the increment was applied, 0 when the limit was reached
     */
    @Modifying
    @Query("UPDATE Coupon c SET c.usedCount = c.usedCount + 1 WHERE c.id = :id "
            + "AND (c.usageLimit IS NULL OR c.usedCount < c.usageLimit)")
    int incrementUsedCountIfWithinLimit(@Param("id") Long couponId);
}