package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CouponRequest;
import com.bhukkad.dto.response.CouponResponse;
import com.bhukkad.entity.Coupon;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.CouponRepository;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceImplTest {

    @Mock
    private CouponRepository couponRepository;
    @Mock
    private RestaurantRepository restaurantRepository;

    @InjectMocks
    private CouponServiceImpl couponService;

    private Coupon validCoupon() {
        Coupon coupon = new Coupon();
        coupon.setId(1L);
        coupon.setCode("SAVE10");
        coupon.setDescription("10 percent off");
        coupon.setDiscountType(Coupon.DiscountType.PERCENTAGE);
        coupon.setDiscountValue(10.0);
        coupon.setMinimumOrderAmount(100.0);
        coupon.setMaximumDiscountAmount(50.0);
        coupon.setValidFrom(LocalDateTime.now().minusDays(1));
        coupon.setValidUntil(LocalDateTime.now().plusDays(1));
        coupon.setUsageLimit(10);
        coupon.setUsedCount(1);
        coupon.setPerUserLimit(1);
        coupon.setActive(true);
        return coupon;
    }

    private CouponRequest couponRequest() {
        CouponRequest request = new CouponRequest();
        request.setCode("save10");
        request.setDescription("10 percent off");
        request.setDiscountType("PERCENTAGE");
        request.setDiscountValue(10.0);
        request.setMinimumOrderAmount(100.0);
        request.setMaximumDiscountAmount(50.0);
        request.setValidFrom(LocalDateTime.of(2026, 1, 1, 0, 0));
        request.setValidUntil(LocalDateTime.of(2026, 12, 31, 0, 0));
        request.setUsageLimit(10);
        request.setPerUserLimit(1);
        return request;
    }

    @Test
    void createCoupon_duplicateCode_throwsBusinessException() {
        Coupon coupon = validCoupon();
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class, () -> couponService.createCoupon(coupon));
        assertEquals("Coupon code already exists", ex.getMessage());
        verify(couponRepository, never()).save(any());
    }

    @Test
    void createCoupon_success() {
        Coupon coupon = validCoupon();
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.empty());
        when(couponRepository.save(coupon)).thenAnswer(inv -> {
            Coupon c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        Coupon saved = couponService.createCoupon(coupon);
        assertEquals(1L, saved.getId());
    }

    @Test
    void getActiveCoupons_withRestaurantId() {
        Coupon coupon = validCoupon();
        when(couponRepository.findActiveCouponsForRestaurant(eq(5L), any(LocalDateTime.class)))
                .thenReturn(List.of(coupon));

        List<Coupon> result = couponService.getActiveCoupons(5L);

        assertEquals(1, result.size());
        verify(couponRepository).findActiveCouponsForRestaurant(eq(5L), any(LocalDateTime.class));
        verify(couponRepository, never()).findActivePlatformCoupons(any());
    }

    @Test
    void getActiveCoupons_nullRestaurantId_usesPlatformCoupons() {
        Coupon coupon = validCoupon();
        when(couponRepository.findActivePlatformCoupons(any(LocalDateTime.class))).thenReturn(List.of(coupon));

        List<Coupon> result = couponService.getActiveCoupons(null);

        assertEquals(1, result.size());
        verify(couponRepository).findActivePlatformCoupons(any(LocalDateTime.class));
    }

    @Test
    void getCouponByCode_notFound() {
        when(couponRepository.findByCode("NONE")).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> couponService.getCouponByCode("NONE"));
        assertEquals("Coupon not found", ex.getMessage());
    }

    @Test
    void getCouponByCode_success() {
        Coupon coupon = validCoupon();
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        assertEquals("SAVE10", couponService.getCouponByCode("SAVE10").getCode());
    }

    @Test
    void validateCoupon_inactive() {
        Coupon coupon = validCoupon();
        coupon.setActive(false);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.validateCoupon("SAVE10", 200.0, 1L));
        assertEquals("Coupon is not active", ex.getMessage());
    }

    @Test
    void validateCoupon_beforeValidFrom() {
        Coupon coupon = validCoupon();
        coupon.setValidFrom(LocalDateTime.now().plusDays(2));
        coupon.setValidUntil(LocalDateTime.now().plusDays(5));
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.validateCoupon("SAVE10", 200.0, 1L));
        assertEquals("Coupon is expired", ex.getMessage());
    }

    @Test
    void validateCoupon_afterValidUntil() {
        Coupon coupon = validCoupon();
        coupon.setValidFrom(LocalDateTime.now().minusDays(5));
        coupon.setValidUntil(LocalDateTime.now().minusDays(1));
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.validateCoupon("SAVE10", 200.0, 1L));
        assertEquals("Coupon is expired", ex.getMessage());
    }

    @Test
    void validateCoupon_usageLimitReached() {
        Coupon coupon = validCoupon();
        coupon.setUsageLimit(5);
        coupon.setUsedCount(5);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.validateCoupon("SAVE10", 200.0, 1L));
        assertEquals("Coupon usage limit reached", ex.getMessage());
    }

    @Test
    void validateCoupon_usageLimitNull_skipsCheck() {
        Coupon coupon = validCoupon();
        coupon.setUsageLimit(null);
        coupon.setUsedCount(999);
        coupon.setMinimumOrderAmount(null);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        Coupon result = couponService.validateCoupon("SAVE10", 10.0, 1L);
        assertEquals("SAVE10", result.getCode());
    }

    @Test
    void validateCoupon_belowMinimumOrder() {
        Coupon coupon = validCoupon();
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.validateCoupon("SAVE10", 50.0, 1L));
        assertEquals("Minimum order amount is ₹100.0", ex.getMessage());
    }

    @Test
    void validateCoupon_restaurantMismatch() {
        Coupon coupon = validCoupon();
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        coupon.setRestaurant(restaurant);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.validateCoupon("SAVE10", 200.0, 1L));
        assertEquals("Coupon not valid for this restaurant", ex.getMessage());
    }

    @Test
    void validateCoupon_restaurantMatch_success() {
        Coupon coupon = validCoupon();
        Restaurant restaurant = new Restaurant();
        restaurant.setId(1L);
        coupon.setRestaurant(restaurant);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        Coupon result = couponService.validateCoupon("SAVE10", 200.0, 1L);
        assertEquals(1L, result.getId());
    }

    @Test
    void validateCoupon_platformCoupon_success() {
        Coupon coupon = validCoupon();
        coupon.setRestaurant(null);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        Coupon result = couponService.validateCoupon("SAVE10", 200.0, 99L);
        assertEquals("SAVE10", result.getCode());
    }

    @Test
    void validateCoupon_usageBelowLimitAndMinOrderMet() {
        Coupon coupon = validCoupon();
        coupon.setUsedCount(4);
        coupon.setUsageLimit(5);
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        assertEquals("SAVE10", couponService.validateCoupon("SAVE10", 100.0, null).getCode());
    }

    @Test
    void calculateDiscount_percentageWithoutCap() {
        Coupon coupon = validCoupon();
        coupon.setMaximumDiscountAmount(null);

        assertEquals(20.0, couponService.calculateDiscount(coupon, 200.0));
    }

    @Test
    void calculateDiscount_percentageCappedAtMaximum() {
        Coupon coupon = validCoupon();
        coupon.setDiscountValue(50.0);
        coupon.setMaximumDiscountAmount(30.0);

        assertEquals(30.0, couponService.calculateDiscount(coupon, 200.0));
    }

    @Test
    void calculateDiscount_percentageEqualToMaximum_notCapped() {
        Coupon coupon = validCoupon();
        coupon.setDiscountValue(10.0);
        coupon.setMaximumDiscountAmount(20.0);

        assertEquals(20.0, couponService.calculateDiscount(coupon, 200.0));
    }

    @Test
    void calculateDiscount_fixedAmount() {
        Coupon coupon = validCoupon();
        coupon.setDiscountType(Coupon.DiscountType.FIXED_AMOUNT);
        coupon.setDiscountValue(25.0);
        coupon.setMaximumDiscountAmount(null);

        assertEquals(25.0, couponService.calculateDiscount(coupon, 200.0));
    }

    @Test
    void calculateDiscount_fixedAmountCapped() {
        Coupon coupon = validCoupon();
        coupon.setDiscountType(Coupon.DiscountType.FIXED_AMOUNT);
        coupon.setDiscountValue(80.0);
        coupon.setMaximumDiscountAmount(40.0);

        assertEquals(40.0, couponService.calculateDiscount(coupon, 200.0));
    }

    @Test
    void getActiveCouponResponses_mapsList() {
        Coupon coupon = validCoupon();
        when(couponRepository.findActivePlatformCoupons(any(LocalDateTime.class))).thenReturn(List.of(coupon));

        List<CouponResponse> responses = couponService.getActiveCouponResponses(null);

        assertEquals(1, responses.size());
        assertEquals("SAVE10", responses.get(0).getCode());
        assertEquals("PERCENTAGE", responses.get(0).getDiscountType());
        assertNull(responses.get(0).getRestaurantId());
        assertNotNullDates(responses.get(0));
    }

    private void assertNotNullDates(CouponResponse response) {
        org.junit.jupiter.api.Assertions.assertNotNull(response.getValidFrom());
        org.junit.jupiter.api.Assertions.assertNotNull(response.getValidUntil());
    }

    @Test
    void validateAndGetResponse_success() {
        Coupon coupon = validCoupon();
        when(couponRepository.findByCode("SAVE10")).thenReturn(Optional.of(coupon));

        CouponResponse response = couponService.validateAndGetResponse("SAVE10", 200.0, null);

        assertEquals("SAVE10", response.getCode());
        assertEquals(10.0, response.getDiscountValue());
    }

    @Test
    void createCouponFromRequest_duplicateCode() {
        when(couponRepository.findByCode("save10")).thenReturn(Optional.of(validCoupon()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> couponService.createCouponFromRequest(couponRequest()));
        assertEquals("Coupon code already exists", ex.getMessage());
    }

    @Test
    void createCouponFromRequest_platformCoupon_uppercasesCode() {
        CouponRequest request = couponRequest();
        when(couponRepository.findByCode("save10")).thenReturn(Optional.empty());
        when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> {
            Coupon c = inv.getArgument(0);
            c.setId(2L);
            return c;
        });

        CouponResponse response = couponService.createCouponFromRequest(request);

        assertEquals("SAVE10", response.getCode());
        assertEquals(2L, response.getId());
        assertEquals(0, response.getUsedCount());
        assertTrue(response.getActive());
        assertNull(response.getRestaurantId());
        verify(restaurantRepository, never()).findById(any());
    }

    @Test
    void createCouponFromRequest_restaurantNotFound() {
        CouponRequest request = couponRequest();
        request.setRestaurantId(9L);
        when(couponRepository.findByCode("save10")).thenReturn(Optional.empty());
        when(restaurantRepository.findById(9L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> couponService.createCouponFromRequest(request));
        assertEquals("Restaurant not found", ex.getMessage());
    }

    @Test
    void createCouponFromRequest_withRestaurant() {
        CouponRequest request = couponRequest();
        request.setRestaurantId(9L);
        request.setDiscountType("FIXED_AMOUNT");
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        restaurant.setName("Spice Hub");
        when(couponRepository.findByCode("save10")).thenReturn(Optional.empty());
        when(restaurantRepository.findById(9L)).thenReturn(Optional.of(restaurant));
        when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> {
            Coupon c = inv.getArgument(0);
            c.setId(3L);
            return c;
        });

        CouponResponse response = couponService.createCouponFromRequest(request);

        assertEquals(9L, response.getRestaurantId());
        assertEquals("Spice Hub", response.getRestaurantName());
        assertEquals("FIXED_AMOUNT", response.getDiscountType());
    }

    @Test
    void createCouponFromRequest_invalidDiscountType_throwsIllegalArgumentException() {
        CouponRequest request = couponRequest();
        request.setDiscountType("BOGUS");
        when(couponRepository.findByCode("save10")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> couponService.createCouponFromRequest(request));
    }

    @Test
    void updateCoupon_notFound() {
        when(couponRepository.findByIdWithRestaurant(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> couponService.updateCoupon(99L, couponRequest()));
        assertEquals("Coupon not found", ex.getMessage());
    }

    @Test
    void updateCoupon_doesNotChangeCodeRestaurantUsedCountOrActive() {
        Coupon coupon = validCoupon();
        coupon.setCode("ORIGINAL");
        coupon.setUsedCount(7);
        coupon.setActive(true);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(4L);
        restaurant.setName("Old Place");
        coupon.setRestaurant(restaurant);
        CouponRequest request = couponRequest();
        request.setCode("CHANGED");
        request.setRestaurantId(99L);
        request.setDescription("updated");
        request.setDiscountType("FIXED_AMOUNT");
        request.setDiscountValue(15.0);

        when(couponRepository.findByIdWithRestaurant(1L)).thenReturn(Optional.of(coupon));
        when(couponRepository.save(coupon)).thenReturn(coupon);

        CouponResponse response = couponService.updateCoupon(1L, request);

        assertEquals("ORIGINAL", coupon.getCode());
        assertEquals(7, coupon.getUsedCount());
        assertTrue(coupon.getActive());
        assertEquals(4L, coupon.getRestaurant().getId());
        assertEquals("updated", response.getDescription());
        assertEquals("FIXED_AMOUNT", response.getDiscountType());
        assertEquals(15.0, response.getDiscountValue());
        verify(restaurantRepository, never()).findById(any());
    }

    @Test
    void deactivateCoupon_notFound() {
        when(couponRepository.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> couponService.deactivateCoupon(99L));
        assertEquals("Coupon not found", ex.getMessage());
    }

    @Test
    void deactivateCoupon_success() {
        Coupon coupon = validCoupon();
        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponRepository.save(coupon)).thenReturn(coupon);

        couponService.deactivateCoupon(1L);

        assertFalse(coupon.getActive());
        verify(couponRepository).save(coupon);
    }

    @Test
    void mapToResponse_nullDatesAndRestaurant() {
        Coupon coupon = validCoupon();
        coupon.setValidFrom(null);
        coupon.setValidUntil(null);
        coupon.setRestaurant(null);
        when(couponRepository.findActivePlatformCoupons(any())).thenReturn(List.of(coupon));

        CouponResponse response = couponService.getActiveCouponResponses(null).get(0);

        assertNull(response.getValidFrom());
        assertNull(response.getValidUntil());
        assertNull(response.getRestaurantId());
        assertNull(response.getRestaurantName());
    }
}
