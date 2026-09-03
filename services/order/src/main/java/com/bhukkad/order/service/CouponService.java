package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.CouponRequest;
import com.bhukkad.order.api.CouponResponse;
import com.bhukkad.order.domain.Coupon;
import com.bhukkad.order.domain.CouponRepository;
import com.bhukkad.order.domain.CouponUsage;
import com.bhukkad.order.domain.CouponUsageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Coupon lifecycle extracted from the monolith (CouponServiceImpl):
 * creation, validation, discount calculation and usage tracking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponRepository couponRepository;
    private final CouponUsageRepository couponUsageRepository;

    @Transactional
    public CouponResponse createCoupon(CouponRequest request) {
        if (couponRepository.findByCode(request.code()).isPresent()) {
            throw new BusinessException("Coupon code already exists");
        }
        Coupon coupon = new Coupon();
        coupon.setCode(request.code().toUpperCase());
        coupon.setDescription(request.description());
        coupon.setDiscountType(Coupon.DiscountType.valueOf(request.discountType()));
        coupon.setDiscountValue(request.discountValue());
        coupon.setMinimumOrderAmount(request.minimumOrderAmount());
        coupon.setMaximumDiscountAmount(request.maximumDiscountAmount());
        coupon.setValidFrom(request.validFrom());
        coupon.setValidUntil(request.validUntil());
        coupon.setUsageLimit(request.usageLimit());
        coupon.setUsedCount(0);
        coupon.setPerUserLimit(request.perUserLimit());
        coupon.setActive(true);
        coupon.setRestaurantId(request.restaurantId());
        return toResponse(couponRepository.save(coupon));
    }

    @Transactional
    public CouponResponse updateCoupon(Long couponId, CouponRequest request) {
        Coupon coupon = findOrThrow(couponId);
        coupon.setDescription(request.description());
        coupon.setDiscountType(Coupon.DiscountType.valueOf(request.discountType()));
        coupon.setDiscountValue(request.discountValue());
        coupon.setMinimumOrderAmount(request.minimumOrderAmount());
        coupon.setMaximumDiscountAmount(request.maximumDiscountAmount());
        coupon.setValidFrom(request.validFrom());
        coupon.setValidUntil(request.validUntil());
        coupon.setUsageLimit(request.usageLimit());
        coupon.setPerUserLimit(request.perUserLimit());
        coupon.setRestaurantId(request.restaurantId());
        return toResponse(couponRepository.save(coupon));
    }

    @Transactional
    public void deactivateCoupon(Long couponId) {
        Coupon coupon = findOrThrow(couponId);
        coupon.setActive(false);
        couponRepository.save(coupon);
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> getActiveCouponResponses(Long restaurantId) {
        LocalDateTime now = LocalDateTime.now();
        List<Coupon> coupons = restaurantId != null
                ? couponRepository.findActiveCouponsForRestaurant(restaurantId, now)
                : couponRepository.findActivePlatformCoupons(now);
        return coupons.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public CouponResponse validateAndGetResponse(String code, BigDecimal orderAmount,
                                                 Long restaurantId, Long customerId) {
        return toResponse(validate(code, orderAmount, restaurantId, customerId));
    }

    /**
     * Validates a coupon against the usual rules: active, within validity window,
     * within usage/per-user limits, minimum order amount, and restaurant scope.
     *
     * @return the valid coupon
     * @throws BusinessException when any rule fails
     */
    @Transactional(readOnly = true)
    public Coupon validate(String code, BigDecimal orderAmount, Long restaurantId, Long customerId) {
        Coupon coupon = couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found: " + code));

        if (!Boolean.TRUE.equals(coupon.getActive())) {
            throw new BusinessException("Coupon is not active");
        }

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw new BusinessException("Coupon is expired");
        }

        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BusinessException("Coupon usage limit reached");
        }

        if (customerId != null && coupon.getPerUserLimit() != null) {
            long userUsage = couponUsageRepository.countByCouponIdAndCustomerId(coupon.getId(), customerId);
            if (userUsage >= coupon.getPerUserLimit()) {
                throw new BusinessException("You have already used this coupon the maximum number of times");
            }
        }

        if (coupon.getMinimumOrderAmount() != null && orderAmount != null
                && orderAmount.compareTo(coupon.getMinimumOrderAmount()) < 0) {
            throw new BusinessException("Minimum order amount is " + coupon.getMinimumOrderAmount());
        }

        if (coupon.getRestaurantId() != null && !coupon.getRestaurantId().equals(restaurantId)) {
            throw new BusinessException("Coupon not valid for this restaurant");
        }

        return coupon;
    }

    /**
     * Calculates the discount for an order amount, capped at the coupon's
     * maximum discount.
     */
    public BigDecimal calculateDiscount(Coupon coupon, BigDecimal orderAmount) {
        if (orderAmount == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal discount;
        if (coupon.getDiscountType() == Coupon.DiscountType.PERCENTAGE) {
            if (coupon.getDiscountValue() == null) {
                return BigDecimal.ZERO;
            }
            discount = orderAmount.multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        } else {
            discount = coupon.getDiscountValue() != null ? coupon.getDiscountValue() : BigDecimal.ZERO;
        }

        if (coupon.getMaximumDiscountAmount() != null
                && discount.compareTo(coupon.getMaximumDiscountAmount()) > 0) {
            discount = coupon.getMaximumDiscountAmount();
        }
        return discount.max(BigDecimal.ZERO);
    }

    /**
     * Records coupon usage and atomically increments the used count within the
     * usage limit. Fails the surrounding transaction if the limit was reached
     * concurrently.
     */
    @Transactional
    public void recordCouponUsage(Long couponId, Long customerId, Long orderId) {
        Coupon coupon = findOrThrow(couponId);
        int updated = couponRepository.incrementUsedCountIfWithinLimit(couponId);
        if (updated != 1) {
            throw new BusinessException("Coupon usage limit reached");
        }
        coupon.setUsedCount(coupon.getUsedCount() + 1);

        if (customerId == null) {
            return;
        }
        CouponUsage usage = new CouponUsage();
        usage.setCouponId(couponId);
        usage.setCustomerId(customerId);
        usage.setOrderId(orderId);
        couponUsageRepository.save(usage);
    }

    private Coupon findOrThrow(Long id) {
        return couponRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found: " + id));
    }

    private CouponResponse toResponse(Coupon coupon) {
        return new CouponResponse(
                coupon.getId(),
                coupon.getCode(),
                coupon.getDescription(),
                coupon.getDiscountType().name(),
                coupon.getDiscountValue(),
                coupon.getMinimumOrderAmount(),
                coupon.getMaximumDiscountAmount(),
                coupon.getValidFrom(),
                coupon.getValidUntil(),
                coupon.getUsageLimit(),
                coupon.getUsedCount(),
                coupon.getPerUserLimit(),
                coupon.getActive(),
                coupon.getRestaurantId());
    }
}