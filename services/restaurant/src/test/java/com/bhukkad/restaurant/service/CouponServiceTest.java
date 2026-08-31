package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.restaurant.domain.Coupon;
import com.bhukkad.restaurant.domain.CouponRepository;
import com.bhukkad.restaurant.domain.CouponUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

    @Mock private CouponRepository couponRepository;
    @Mock private CouponUsageRepository usageRepository;
    @InjectMocks private CouponService service;

    private Coupon percentCoupon(BigDecimal value, BigDecimal max, LocalDateTime validUntil) {
        Coupon c = new Coupon();
        c.setId(1L);
        c.setCode("P10");
        c.setDiscountType(Coupon.DISCOUNT_PERCENT);
        c.setDiscountValue(value);
        c.setMaxDiscount(max);
        c.setValidUntil(validUntil);
        c.setMinOrderAmount(BigDecimal.ZERO);
        return c;
    }

    @Test
    void percentDiscount_cappedByMax() {
        Coupon coupon = percentCoupon(new BigDecimal("10.00"), new BigDecimal("50.00"),
                LocalDateTime.now().plusDays(1));
        when(couponRepository.findByCodeAndActiveTrue("P10")).thenReturn(Optional.of(coupon));

        BigDecimal discount = service.discount("P10", 1L, null, new BigDecimal("1000.00"));

        assertThat(discount).isEqualByComparingTo("50.00");
    }

    @Test
    void percentDiscount_withoutMax() {
        Coupon coupon = percentCoupon(new BigDecimal("10.00"), null, LocalDateTime.now().plusDays(1));
        when(couponRepository.findByCodeAndActiveTrue("P10")).thenReturn(Optional.of(coupon));

        BigDecimal discount = service.discount("P10", 1L, null, new BigDecimal("200.00"));

        assertThat(discount).isEqualByComparingTo("20.00");
    }

    @Test
    void expiredCoupon_throws() {
        Coupon coupon = percentCoupon(new BigDecimal("10.00"), null, LocalDateTime.now().minusDays(1));
        when(couponRepository.findByCodeAndActiveTrue("P10")).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.discount("P10", 1L, null, new BigDecimal("200.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void belowMinOrder_throws() {
        Coupon coupon = percentCoupon(new BigDecimal("10.00"), null, LocalDateTime.now().plusDays(1));
        coupon.setMinOrderAmount(new BigDecimal("500.00"));
        when(couponRepository.findByCodeAndActiveTrue("P10")).thenReturn(Optional.of(coupon));

        assertThatThrownBy(() -> service.discount("P10", 1L, null, new BigDecimal("200.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("minimum");
    }

    @Test
    void unknownCoupon_throws() {
        when(couponRepository.findByCodeAndActiveTrue("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.discount("NOPE", 1L, null, new BigDecimal("100.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid or inactive");
    }

    @Test
    void discountNeverExceedsOrderAmount() {
        Coupon coupon = percentCoupon(new BigDecimal("50.00"), null, LocalDateTime.now().plusDays(1));
        when(couponRepository.findByCodeAndActiveTrue("P10")).thenReturn(Optional.of(coupon));

        BigDecimal discount = service.discount("P10", 1L, null, new BigDecimal("100.00"));

        assertThat(discount).isEqualByComparingTo("50.00");
    }
}
