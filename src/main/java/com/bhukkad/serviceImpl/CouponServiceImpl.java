package com.bhukkad.serviceImpl;

import com.bhukkad.entity.Coupon;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CouponRepository;
import com.bhukkad.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponRepository couponRepository;

    @Override
    @Transactional
    public Coupon createCoupon(Coupon coupon) {
        // Validate coupon code uniqueness
        if (couponRepository.findByCode(coupon.getCode()).isPresent()) {
            throw new BusinessException("Coupon code already exists");
        }

        return couponRepository.save(coupon);
    }

    @Override
    public List<Coupon> getActiveCoupons(Long restaurantId) {
        LocalDateTime now = LocalDateTime.now();
        if (restaurantId != null) {
            return couponRepository.findActiveCouponsForRestaurant(restaurantId, now);
        } else {
            return couponRepository.findActivePlatformCoupons(now);
        }
    }

    @Override
    public Coupon getCouponByCode(String code) {
        return couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
    }

    @Override
    public Coupon validateCoupon(String code, Double orderAmount, Long restaurantId) {
        Coupon coupon = getCouponByCode(code);

        // Check if coupon is active
        if (!coupon.getActive()) {
            throw new BusinessException("Coupon is not active");
        }

        // Check validity dates
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw new BusinessException("Coupon is not valid at this time");
        }

        // Check usage limit
        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BusinessException("Coupon usage limit reached");
        }

        // Check minimum order amount
        if (coupon.getMinimumOrderAmount() != null && orderAmount < coupon.getMinimumOrderAmount()) {
            throw new BusinessException("Minimum order amount for this coupon is ₹" + coupon.getMinimumOrderAmount());
        }

        // Check restaurant applicability
        if (coupon.getRestaurant() != null && !coupon.getRestaurant().getId().equals(restaurantId)) {
            throw new BusinessException("Coupon is not valid for this restaurant");
        }

        return coupon;
    }

    @Override
    public Double calculateDiscount(Coupon coupon, Double orderAmount) {
        double discount = 0.0;

        if (coupon.getDiscountType() == Coupon.DiscountType.PERCENTAGE) {
            discount = (orderAmount * coupon.getDiscountValue()) / 100;
        } else {
            discount = coupon.getDiscountValue();
        }

        // Apply maximum discount limit if exists
        if (coupon.getMaximumDiscountAmount() != null && discount > coupon.getMaximumDiscountAmount()) {
            discount = coupon.getMaximumDiscountAmount();
        }

        return discount;
    }
}