package com.bhukkad.service;

import com.bhukkad.entity.Coupon;

import java.util.List;

public interface CouponService {
    Coupon createCoupon(Coupon coupon);
    List<Coupon> getActiveCoupons(Long restaurantId);
    Coupon getCouponByCode(String code);
    Coupon validateCoupon(String code, Double orderAmount, Long restaurantId);
    Double calculateDiscount(Coupon coupon, Double orderAmount);
}