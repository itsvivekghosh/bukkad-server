package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CouponRequest;
import com.bhukkad.dto.response.CouponResponse;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.CouponUsage;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CouponRepository;
import com.bhukkad.repository.CouponUsageRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CouponServiceImpl implements CouponService {

    private final CouponRepository couponRepository;
    private final RestaurantRepository restaurantRepository;
    private final CouponUsageRepository couponUsageRepository;
    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;

    @Override
    public Coupon createCoupon(Coupon coupon) {
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
        }
        return couponRepository.findActivePlatformCoupons(now);
    }

    @Override
    public Coupon getCouponByCode(String code) {
        return couponRepository.findByCode(code)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
    }

    @Override
    public Coupon validateCoupon(String code, Double orderAmount, Long restaurantId) {
        return validateCoupon(code, orderAmount, restaurantId, null);
    }

    @Override
    public Coupon validateCoupon(String code, Double orderAmount, Long restaurantId, Long customerId) {
        Coupon coupon = getCouponByCode(code);

        if (!coupon.getActive()) throw new BusinessException("Coupon is not active");

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
    public List<CouponResponse> getActiveCouponResponses(Long restaurantId) {
        return getActiveCoupons(restaurantId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    public CouponResponse validateAndGetResponse(String code, Double orderAmount, Long restaurantId) {
        return validateAndGetResponse(code, orderAmount, restaurantId, null);
    }

    @Override
    public CouponResponse validateAndGetResponse(String code, Double orderAmount, Long restaurantId, Long customerId) {
        Coupon coupon = validateCoupon(code, orderAmount, restaurantId, customerId);
        return mapToResponse(coupon);
    }

    @Override
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
    public CouponResponse updateCoupon(Long couponId, CouponRequest request) {
        Coupon coupon = couponRepository.findByIdWithRestaurant(couponId)
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
    public void deactivateCoupon(Long couponId) {
        Coupon coupon = couponRepository.findById(couponId)
                .orElseThrow(() -> new ResourceNotFoundException("Coupon not found"));
        coupon.setActive(false);
        couponRepository.save(coupon);
    }

    @Override
    public void recordCouponUsage(Coupon coupon) {
        recordCouponUsage(coupon, null, null);
    }

    @Override
    public void recordCouponUsage(Coupon coupon, Long customerId, Long orderId) {
        coupon.setUsedCount(coupon.getUsedCount() + 1);
        couponRepository.save(coupon);

        if (customerId == null) {
            return;
        }
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        CouponUsage usage = new CouponUsage();
        usage.setCoupon(coupon);
        usage.setCustomer(customer);
        if (orderId != null) {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
            usage.setOrder(order);
        }
        couponUsageRepository.save(usage);
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