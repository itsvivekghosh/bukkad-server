package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.dto.request.CouponRequest;
import com.bhukkad.order.api.dto.response.CouponResponse;
import com.bhukkad.order.domain.entity.Coupon;
import com.bhukkad.order.domain.repository.CouponRepository;
import com.bhukkad.order.domain.repository.CouponUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock private CouponRepository couponRepository;
    @Mock private CouponUsageRepository couponUsageRepository;

    @InjectMocks private CouponService service;

    private Coupon coupon(long id, String code, Coupon.DiscountType type, BigDecimal value,
                          BigDecimal minOrder, BigDecimal maxDiscount, Integer usageLimit,
                          Integer usedCount, boolean active, LocalDateTime validFrom,
                          LocalDateTime validUntil, Long restaurantId) {
        Coupon c = new Coupon();
        c.setId(id);
        c.setCode(code);
        c.setDescription("Test coupon");
        c.setDiscountType(type);
        c.setDiscountValue(value);
        c.setMinimumOrderAmount(minOrder);
        c.setMaximumDiscountAmount(maxDiscount);
        c.setUsageLimit(usageLimit);
        c.setUsedCount(usedCount);
        c.setPerUserLimit(1);
        c.setActive(active);
        c.setValidFrom(validFrom);
        c.setValidUntil(validUntil);
        c.setRestaurantId(restaurantId);
        return c;
    }

    private CouponRequest request(String code) {
        return new CouponRequest(code, "20% off", "PERCENTAGE", new BigDecimal("20"),
                new BigDecimal("100"), new BigDecimal("50"),
                LocalDateTime.now().minusDays(1), LocalDateTime.now().plusDays(30),
                100, 1, null);
    }

    @Test
    void createCoupon_uppercasesCodeAndSaves() {
        when(couponRepository.findByCode("save20")).thenReturn(Optional.empty());
        when(couponRepository.save(any(Coupon.class))).thenAnswer(inv -> {
            Coupon c = inv.getArgument(0);
            c.setId(1L);
            return c;
        });

        CouponResponse response = service.createCoupon(request("save20"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.code()).isEqualTo("SAVE20");
        assertThat(response.active()).isTrue();
    }

    @Test
    void createCoupon_duplicateCode_throws() {
        when(couponRepository.findByCode("save20")).thenReturn(Optional.of(new Coupon()));

        assertThatThrownBy(() -> service.createCoupon(request("save20")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void getActiveCoupons_platformWide_returnsList() {
        LocalDateTime now = LocalDateTime.now();
        Coupon platform = coupon(1L, "FLAT10", Coupon.DiscountType.FIXED_AMOUNT, new BigDecimal("10"),
                null, null, null, 0, true, now.minusDays(1), now.plusDays(1), null);
        when(couponRepository.findActivePlatformCoupons(any())).thenReturn(List.of(platform));

        List<CouponResponse> responses = service.getActiveCouponResponses(null);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).code()).isEqualTo("FLAT10");
    }

    @Test
    void validateCoupon_inactive_throws() {
        LocalDateTime now = LocalDateTime.now();
        Coupon inactive = coupon(1L, "OLD", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, null, 0, false, now.minusDays(2), now.plusDays(2), null);
        when(couponRepository.findByCode("OLD")).thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> service.validate("OLD", new BigDecimal("500"), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void validateCoupon_expired_throws() {
        LocalDateTime now = LocalDateTime.now();
        Coupon expired = coupon(1L, "EXP", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, null, 0, true, now.minusDays(2), now.minusDays(1), null);
        when(couponRepository.findByCode("EXP")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.validate("EXP", new BigDecimal("500"), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void validateCoupon_usageLimitReached_throws() {
        LocalDateTime now = LocalDateTime.now();
        Coupon usedUp = coupon(1L, "USED", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, 5, 5, true, now.minusDays(1), now.plusDays(1), null);
        when(couponRepository.findByCode("USED")).thenReturn(Optional.of(usedUp));

        assertThatThrownBy(() -> service.validate("USED", new BigDecimal("500"), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("usage limit");
    }

    @Test
    void validateCoupon_minOrderNotMet_throws() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(1L, "MIN", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                new BigDecimal("300"), null, null, 0, true, now.minusDays(1), now.plusDays(1), null);
        when(couponRepository.findByCode("MIN")).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.validate("MIN", new BigDecimal("100"), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Minimum order amount");
    }

    @Test
    void validateCoupon_restaurantScoped_mismatch_throws() {
        LocalDateTime now = LocalDateTime.now();
        Coupon scoped = coupon(1L, "REST", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, null, 0, true, now.minusDays(1), now.plusDays(1), 10L);
        when(couponRepository.findByCode("REST")).thenReturn(Optional.of(scoped));

        assertThatThrownBy(() -> service.validate("REST", new BigDecimal("500"), 99L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not valid for this restaurant");
    }

    @Test
    void validateCoupon_valid_returnsCoupon() {
        LocalDateTime now = LocalDateTime.now();
        Coupon valid = coupon(1L, "OK", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, null, 0, true, now.minusDays(1), now.plusDays(1), null);
        when(couponRepository.findByCode("OK")).thenReturn(Optional.of(valid));

        Coupon result = service.validate("OK", new BigDecimal("500"), null, null);

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void calculateDiscount_percentage_cappedByMax() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(1L, "CAP", Coupon.DiscountType.PERCENTAGE, new BigDecimal("50"),
                null, new BigDecimal("20"), null, 0, true, now.minusDays(1), now.plusDays(1), null);

        BigDecimal discount = service.calculateDiscount(coupon, new BigDecimal("1000"));

        assertThat(discount).isEqualByComparingTo("20.00");
    }

    @Test
    void calculateDiscount_fixedAmount() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(1L, "FIX", Coupon.DiscountType.FIXED_AMOUNT, new BigDecimal("75"),
                null, null, null, 0, true, now.minusDays(1), now.plusDays(1), null);

        BigDecimal discount = service.calculateDiscount(coupon, new BigDecimal("1000"));

        assertThat(discount).isEqualByComparingTo("75.00");
    }

    @Test
    void recordCouponUsage_incrementsAndSavesUsage() {
        Coupon coupon = new Coupon();
        coupon.setId(1L);
        coupon.setUsedCount(0);
        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponRepository.incrementUsedCountIfWithinLimit(1L)).thenReturn(1);
        when(couponUsageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.recordCouponUsage(1L, 5L, 9L);

        verify(couponRepository).incrementUsedCountIfWithinLimit(1L);
        assertThat(coupon.getUsedCount()).isEqualTo(1);
        verify(couponUsageRepository).save(any());
    }

    @Test
    void recordCouponUsage_limitReached_throws() {
        Coupon coupon = new Coupon();
        coupon.setId(1L);
        when(couponRepository.findById(1L)).thenReturn(Optional.of(coupon));
        when(couponRepository.incrementUsedCountIfWithinLimit(1L)).thenReturn(0);

        assertThatThrownBy(() -> service.recordCouponUsage(1L, 5L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("usage limit reached");
    }

    @Test
    void deactivateCoupon_unknown_throws() {
        when(couponRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateCoupon(9L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("9");
    }

    @Test
    void updateCoupon_unknown_throws() {
        when(couponRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateCoupon(9L, request("X")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("9");
    }

    @Test
    void validateCoupon_perUserLimitReached_throws() {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = coupon(1L, "PER", Coupon.DiscountType.PERCENTAGE, new BigDecimal("10"),
                null, null, null, 0, true, now.minusDays(1), now.plusDays(1), null);
        coupon.setPerUserLimit(1);
        when(couponRepository.findByCode("PER")).thenReturn(Optional.of(coupon));
        when(couponUsageRepository.countByCouponIdAndCustomerId(1L, 5L)).thenReturn(1L);

        assertThatThrownBy(() -> service.validate("PER", new BigDecimal("500"), null, 5L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maximum number of times");
    }

    @Test
    void validateCoupon_unknownCode_throws() {
        when(couponRepository.findByCode("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.validate("NOPE", new BigDecimal("500"), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}