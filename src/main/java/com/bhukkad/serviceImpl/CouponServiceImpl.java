package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CouponRequest;
import com.bhukkad.dto.response.CouponResponse;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CouponRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponRepository couponRepository;
    private final RestaurantRepository restaurantRepository;

    @Override
    @Transactional
    public Coupon createCoupon(Coupon coupon) {
        if (couponRepository.findByCode(coupon.getCode()).isPresent()) {
            throw new BusinessException("Coupon code already exists");
        }
        return couponRepository.save(coupon);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> getActiveCoupons(Long restaurantId) {
        LocalDateTime now = LocalDateTime.now();
        if (restaurantId != null) {
            return couponRepository.findActiveCouponsForRestaurant(restaurantId, now);
        }
        return couponRepository.findActivePlatformCoupons(now);
    }

    @Override
    @Transactional(readOnly = true)
    public Coupon getCouponByCode(String code) {
        return couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
    }

    @Override
    public Coupon validateCoupon(String code, Double orderAmount, Long restaurantId) {
        Coupon coupon = getCouponByCode(code);

        if (!coupon.getActive()) throw new BusinessException("Coupon is not active");

        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw new BusinessException("Coupon is expired");
        }

        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw new BusinessException("Coupon usage limit reached");
        }

        if (coupon.getMinimumOrderAmount() != null && orderAmount < coupon.getMinimumOrderAmount()) {
            throw new BusinessException("Minimum order amount is ₹" + coupon.getMinimumOrderAmount());
        }

        if (coupon.getRestaurant() != null && !coupon.getRestaurant().getId().equals(restaurantId)) {
            throw new BusinessException("Coupon not valid for this restaurant");
        }

        return coupon;
    }

    @Override
    public Double calculateDiscount(Coupon coupon, Double orderAmount) {
        double discount;
        if (coupon.getDiscountType() == Coupon.DiscountType.PERCENTAGE) {
            discount = (orderAmount * coupon.getDiscountValue()) / 100;
        } else {
            discount = coupon.getDiscountValue();
        }

        if (coupon.getMaximumDiscountAmount() != null && discount > coupon.getMaximumDiscountAmount()) {
            discount = coupon.getMaximumDiscountAmount();
        }

        return discount;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponResponse> getActiveCouponResponses(Long restaurantId) {
        return getActiveCoupons(restaurantId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public CouponResponse validateAndGetResponse(String code, Double orderAmount, Long restaurantId) {
        Coupon coupon = validateCoupon(code, orderAmount, restaurantId);
        Double discount = calculateDiscount(coupon, orderAmount);

        CouponResponse response = mapToResponse(coupon);
        return response;
    }

    @Override
    @Transactional
    public CouponResponse createCouponFromRequest(CouponRequest request) {
        if (couponRepository.findByCode(request.getCode()).isPresent()) {
            throw new BusinessException("Coupon code already exists");
        }

        Coupon coupon = new Coupon();
        coupon.setCode(request.getCode().toUpperCase());
        coupon.setDescription(request.getDescription());
        coupon.setDiscountType(Coupon.DiscountType.valueOf(request.getDiscountType()));
        coupon.setDiscountValue(request.getDiscountValue());
        coupon.setMinimumOrderAmount(request.getMinimumOrderAmount());
        coupon.setMaximumDiscountAmount(request.getMaximumDiscountAmount());
        coupon.setValidFrom(request.getValidFrom());
        coupon.setValidUntil(request.getValidUntil());
        coupon.setUsageLimit(request.getUsageLimit());
        coupon.setUsedCount(0);
        coupon.setPerUserLimit(request.getPerUserLimit());
        coupon.setActive(true);

        if (request.getRestaurantId() != null) {
            Restaurant restaurant = restaurantRepository.findById(request.getRestaurantId())
                    .orElseThrow(() -> new ResourceNotFoundException("Restaurant not found"));
            coupon.setRestaurant(restaurant);
        }

        coupon = couponRepository.save(coupon);
        return mapToResponse(coupon);
    }

    @Override
    @Transactional
    public CouponResponse updateCoupon(Long couponId, CouponRequest request) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));

        coupon.setDescription(request.getDescription());
        coupon.setDiscountType(Coupon.DiscountType.valueOf(request.getDiscountType()));
        coupon.setDiscountValue(request.getDiscountValue());
        coupon.setMinimumOrderAmount(request.getMinimumOrderAmount());
        coupon.setMaximumDiscountAmount(request.getMaximumDiscountAmount());
        coupon.setValidFrom(request.getValidFrom());
        coupon.setValidUntil(request.getValidUntil());
        coupon.setUsageLimit(request.getUsageLimit());
        coupon.setPerUserLimit(request.getPerUserLimit());

        coupon = couponRepository.save(coupon);
        return mapToResponse(coupon);
    }

    @Override
    @Transactional
    public void deactivateCoupon(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
        coupon.setActive(false);
        couponRepository.save(coupon);
    }

    private CouponResponse mapToResponse(Coupon coupon) {
        return CouponResponse.builder()
                .id(coupon.getId())
                .code(coupon.getCode())
                .description(coupon.getDescription())
                .discountType(coupon.getDiscountType().name())
                .discountValue(coupon.getDiscountValue())
                .minimumOrderAmount(coupon.getMinimumOrderAmount())
                .maximumDiscountAmount(coupon.getMaximumDiscountAmount())
                .validFrom(coupon.getValidFrom() != null ? coupon.getValidFrom().toString() : null)
                .validUntil(coupon.getValidUntil() != null ? coupon.getValidUntil().toString() : null)
                .usageLimit(coupon.getUsageLimit())
                .usedCount(coupon.getUsedCount())
                .perUserLimit(coupon.getPerUserLimit())
                .active(coupon.getActive())
                .restaurantId(coupon.getRestaurant() != null ? coupon.getRestaurant().getId() : null)
                .restaurantName(coupon.getRestaurant() != null ? coupon.getRestaurant().getName() : null)
                .build();
    }
}