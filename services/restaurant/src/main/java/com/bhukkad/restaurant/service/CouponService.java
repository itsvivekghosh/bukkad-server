package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.Coupon;
import com.bhukkad.restaurant.domain.CouponRepository;
import com.bhukkad.restaurant.domain.CouponUsage;
import com.bhukkad.restaurant.domain.CouponUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Coupon validation + discount computation (Priority 2).
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository usageRepository;

    @Transactional
    public Coupon create(String code, String discountType, BigDecimal discountValue,
                         BigDecimal minOrderAmount, BigDecimal maxDiscount, LocalDateTime validUntil) {
        Coupon coupon = new Coupon();
        coupon.setCode(code);
        coupon.setDiscountType(discountType);
        coupon.setDiscountValue(discountValue);
        coupon.setMinOrderAmount(minOrderAmount != null ? minOrderAmount : BigDecimal.ZERO);
        coupon.setMaxDiscount(maxDiscount);
        coupon.setValidUntil(validUntil);
        return couponRepository.save(coupon);
    }

    @Transactional(readOnly = true)
    public BigDecimal discount(String code, Long customerId, Long orderId, BigDecimal orderAmount) {
        Coupon coupon = couponRepository.findByCodeAndActiveTrue(code)
                .orElseThrow(() -> new BusinessException("Invalid or inactive coupon: " + code));
        LocalDateTime now = LocalDateTime.now();
        if (coupon.getValidFrom() != null && now.isBefore(coupon.getValidFrom())) {
            throw new BusinessException("Coupon not yet valid");
        }
        if (coupon.getValidUntil() != null && now.isAfter(coupon.getValidUntil())) {
            throw new BusinessException("Coupon expired");
        }
        if (orderAmount.compareTo(coupon.getMinOrderAmount()) < 0) {
            throw new BusinessException("Order amount below coupon minimum");
        }
        if (orderId != null && usageRepository.findByCouponIdAndCustomerIdAndOrderId(
                coupon.getId(), customerId, orderId).isPresent()) {
            throw new BusinessException("Coupon already applied to this order");
        }

        BigDecimal discount;
        if (Coupon.DISCOUNT_PERCENT.equals(coupon.getDiscountType())) {
            discount = orderAmount.multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (coupon.getMaxDiscount() != null && discount.compareTo(coupon.getMaxDiscount()) > 0) {
                discount = coupon.getMaxDiscount();
            }
        } else {
            discount = coupon.getDiscountValue();
        }
        if (discount.compareTo(orderAmount) > 0) {
            discount = orderAmount;
        }
        return discount;
    }

    @Transactional
    public CouponUsage recordUsage(Long couponId, Long customerId, Long orderId, BigDecimal discount) {
        CouponUsage usage = new CouponUsage();
        usage.setCouponId(couponId);
        usage.setCustomerId(customerId);
        usage.setOrderId(orderId);
        usage.setDiscount(discount);
        return usageRepository.save(usage);
    }
}