package com.bhukkad.controller;

import com.bhukkad.dto.request.CouponRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CouponResponse;
import com.bhukkad.service.CouponService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CouponControllerTest {

    @Mock
    private CouponService couponService;

    @InjectMocks
    private CouponController couponController;

    @Test
    void getActiveCoupons_returnsList() {
        List<CouponResponse> coupons = List.of(new CouponResponse());
        when(couponService.getActiveCouponResponses(1L)).thenReturn(coupons);

        ResponseEntity<ApiResponse<List<CouponResponse>>> response = couponController.getActiveCoupons(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(coupons, response.getBody().getData());
    }

    @Test
    void getActiveCoupons_withNullRestaurantId() {
        List<CouponResponse> coupons = List.of();
        when(couponService.getActiveCouponResponses(null)).thenReturn(coupons);

        ResponseEntity<ApiResponse<List<CouponResponse>>> response = couponController.getActiveCoupons(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(coupons, response.getBody().getData());
    }

    @Test
    void validateCoupon_returnsCoupon() {
        CouponResponse coupon = new CouponResponse();
        when(couponService.validateAndGetResponse("SAVE10", 500.0, 1L)).thenReturn(coupon);

        ResponseEntity<ApiResponse<CouponResponse>> response =
                couponController.validateCoupon("SAVE10", 500.0, 1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Coupon is valid", response.getBody().getMessage());
        assertEquals(coupon, response.getBody().getData());
    }

    @Test
    void createCoupon_returnsCreated() {
        CouponRequest request = new CouponRequest();
        CouponResponse coupon = new CouponResponse();
        when(couponService.createCouponFromRequest(request)).thenReturn(coupon);

        ResponseEntity<ApiResponse<CouponResponse>> response = couponController.createCoupon(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Coupon created", response.getBody().getMessage());
        assertEquals(coupon, response.getBody().getData());
    }

    @Test
    void updateCoupon_returnsUpdated() {
        CouponRequest request = new CouponRequest();
        CouponResponse coupon = new CouponResponse();
        when(couponService.updateCoupon(5L, request)).thenReturn(coupon);

        ResponseEntity<ApiResponse<CouponResponse>> response = couponController.updateCoupon(5L, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Coupon updated", response.getBody().getMessage());
        assertEquals(coupon, response.getBody().getData());
    }

    @Test
    void deactivateCoupon_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = couponController.deactivateCoupon(5L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Coupon deactivated", response.getBody().getMessage());
        verify(couponService).deactivateCoupon(5L);
    }
}
