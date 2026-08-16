package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.CouponRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CouponResponse;
import com.bhukkad.service.CouponService;
import com.bhukkad.security.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;
    private final SecurityUtils securityUtils;

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<CouponResponse>>> getActiveCoupons(
            @RequestParam(required = false) Long restaurantId) {
        List<CouponResponse> coupons = couponService.getActiveCouponResponses(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(coupons));
    }

    @GetMapping("/validate")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<CouponResponse>> validateCoupon(
            @RequestParam String code,
            @RequestParam Double orderAmount,
            @RequestParam(required = false) Long restaurantId) {
        CouponResponse coupon = couponService.validateAndGetResponse(
                code, orderAmount, restaurantId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Coupon is valid", coupon));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<CouponResponse>> createCoupon(@Valid @RequestBody CouponRequest request) {
        CouponResponse coupon = couponService.createCouponFromRequest(request);
        return ResponseEntity.ok(ApiResponse.success("Coupon created", coupon));
    }

    @PutMapping("/{couponId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CouponResponse>> updateCoupon(
            @PathVariable Long couponId,
            @Valid @RequestBody CouponRequest request) {
        CouponResponse coupon = couponService.updateCoupon(couponId, request);
        return ResponseEntity.ok(ApiResponse.success("Coupon updated", coupon));
    }

    @DeleteMapping("/{couponId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Void>> deactivateCoupon(@PathVariable Long couponId) {
        couponService.deactivateCoupon(couponId);
        return ResponseEntity.ok(ApiResponse.success("Coupon deactivated", null));
    }
}