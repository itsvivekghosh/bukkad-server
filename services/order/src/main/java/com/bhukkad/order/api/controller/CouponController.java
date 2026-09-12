package com.bhukkad.order.api.controller;

import com.bhukkad.order.domain.service.impl.CouponService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import com.bhukkad.order.api.dto.request.CouponRequest;
import com.bhukkad.order.api.dto.response.CouponResponse;
import com.bhukkad.order.domain.entity.Coupon;

/**
 * Coupon API ({@code /api/v1/coupons}). Extracted from the monolith's
 * CouponController — customers browse/validate, admins CRUD.
 */
@RestController
@RequestMapping("/api/v1/coupons")
@RequiredArgsConstructor
public class CouponController {

    private final CouponService couponService;

    @GetMapping("/active")
    public List<CouponResponse> getActiveCoupons(@RequestParam(required = false) Long restaurantId) {
        return couponService.getActiveCouponResponses(restaurantId);
    }

    @GetMapping("/validate")
    public CouponResponse validateCoupon(
            @RequestParam String code,
            @RequestParam BigDecimal orderAmount,
            @RequestParam(required = false) Long restaurantId,
            @RequestParam(required = false) Long customerId) {
        return couponService.validateAndGetResponse(code, orderAmount, restaurantId, customerId);
    }

    @PostMapping
    @org.springframework.security.access.prepost.PreAuthorize(
            "hasRole('ADMIN') or hasRole('RESTAURANT_OWNER')")
    public CouponResponse createCoupon(@Valid @RequestBody CouponRequest request) {
        return couponService.createCoupon(request);
    }

    @PutMapping("/{couponId}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public CouponResponse updateCoupon(@PathVariable Long couponId,
                                       @Valid @RequestBody CouponRequest request) {
        return couponService.updateCoupon(couponId, request);
    }

    @DeleteMapping("/{couponId}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public void deactivateCoupon(@PathVariable Long couponId) {
        couponService.deactivateCoupon(couponId);
    }
}