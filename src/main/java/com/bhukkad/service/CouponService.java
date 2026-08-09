package com.bhukkad.service;

import com.bhukkad.dto.request.CouponRequest;
import com.bhukkad.dto.response.CouponResponse;
import com.bhukkad.entity.Coupon;

import java.util.List;

public interface CouponService {
    Coupon createCoupon(Coupon coupon);
    List<Coupon> getActiveCoupons(Long restaurantId);
    Coupon getCouponByCode(String code);
    Coupon validateCoupon(String code, Double orderAmount, Long restaurantId);
    Double calculateDiscount(Coupon coupon, Double orderAmount);
    List<CouponResponse> getActiveCouponResponses(Long restaurantId);
    CouponResponse validateAndGetResponse(String code, Double orderAmount, Long restaurantId);
    CouponResponse createCouponFromRequest(CouponRequest request);
    CouponResponse updateCoupon(Long couponId, CouponRequest request);
    void deactivateCoupon(Long couponId);
}