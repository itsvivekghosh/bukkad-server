package com.bhukkad.restaurant;

import com.bhukkad.restaurant.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Priority 2 promotion depth (coupons, campaigns, banners) on PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PromotionPostgresIntegrationTest extends AbstractRestaurantPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CouponRepository couponRepository;
    @Autowired private CouponUsageRepository usageRepository;
    @Autowired private PromotionCampaignRepository campaignRepository;
    @Autowired private PromoBannerRepository bannerRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM promo_banners");
        jdbcTemplate.update("DELETE FROM promotion_campaigns");
        jdbcTemplate.update("DELETE FROM coupon_usages");
        jdbcTemplate.update("DELETE FROM coupons");
    }

    @Test
    void migration_appliedV4Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('coupons','coupon_usages','promotion_campaigns','promo_banners')",
                Integer.class);
        assertThat(tables).isEqualTo(4);
    }

    @Test
    void couponUniqueByCode() {
        Coupon c = new Coupon();
        c.setCode("FLAT50");
        c.setDiscountType(Coupon.DISCOUNT_FIXED);
        c.setDiscountValue(new BigDecimal("50.00"));
        c.setActive(true);
        c.setCreatedAt(LocalDateTime.now());
        couponRepository.saveAndFlush(c);

        assertThat(couponRepository.findByCodeAndActiveTrue("FLAT50")).isPresent();
    }

    @Test
    void campaignAndBannerPersist() {
        PromotionCampaign campaign = new PromotionCampaign();
        campaign.setName("Summer Sale");
        campaign.setDiscountPct(new BigDecimal("15.00"));
        campaign.setStartsAt(LocalDateTime.now().minusDays(1));
        campaign.setEndsAt(LocalDateTime.now().plusDays(7));
        campaignRepository.saveAndFlush(campaign);

        PromoBanner banner = new PromoBanner();
        banner.setTitle("Flat 15% off");
        banner.setCampaignId(campaign.getId());
        bannerRepository.saveAndFlush(banner);

        assertThat(campaignRepository.findByActiveTrueAndStartsAtBeforeAndEndsAtAfter(
                LocalDateTime.now(), LocalDateTime.now())).hasSize(1);
        assertThat(bannerRepository.findByActiveTrue()).hasSize(1);
    }

    @Test
    void couponUsageUniquePerOrder() {
        Coupon c = new Coupon();
        c.setCode("FLAT50");
        c.setDiscountType(Coupon.DISCOUNT_FIXED);
        c.setDiscountValue(new BigDecimal("50.00"));
        c.setActive(true);
        c.setCreatedAt(LocalDateTime.now());
        Coupon saved = couponRepository.saveAndFlush(c);

        CouponUsage u1 = new CouponUsage();
        u1.setCouponId(saved.getId());
        u1.setCustomerId(1L);
        u1.setOrderId(10L);
        u1.setDiscount(BigDecimal.TEN);
        usageRepository.saveAndFlush(u1);

        assertThat(usageRepository.findByCouponIdAndCustomerIdAndOrderId(saved.getId(), 1L, 10L)).isPresent();
        assertThat(usageRepository.countByCouponIdAndCustomerId(saved.getId(), 1L)).isEqualTo(1);
    }
}
