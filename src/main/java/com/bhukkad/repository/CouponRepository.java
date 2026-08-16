package com.bhukkad.repository;

import com.bhukkad.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    @Query("SELECT c FROM Coupon c LEFT JOIN FETCH c.restaurant WHERE c.code = :code")
    Optional<Coupon> findByCode(@Param("code") String code);

    @Query("SELECT c FROM Coupon c LEFT JOIN FETCH c.restaurant WHERE c.id = :id")
    Optional<Coupon> findByIdWithRestaurant(@Param("id") Long id);

    @Query("SELECT c FROM Coupon c LEFT JOIN FETCH c.restaurant WHERE c.active = true AND " +
            "c.validFrom <= :now AND c.validUntil >= :now AND " +
            "(c.restaurant.id = :restaurantId OR c.restaurant IS NULL)")
    List<Coupon> findActiveCouponsForRestaurant(@Param("restaurantId") Long restaurantId,
                                                @Param("now") LocalDateTime now);

    @Query("SELECT c FROM Coupon c LEFT JOIN FETCH c.restaurant WHERE c.active = true AND " +
            "c.validFrom <= :now AND c.validUntil >= :now AND c.restaurant IS NULL")
    List<Coupon> findActivePlatformCoupons(@Param("now") LocalDateTime now);
}