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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Priority 2 promotion depth (campaigns + banners) on PG.
 *
 * <p>Coupon persistence moved to the order service's ownership boundary
 * (coupons/coupon_usages are written by order), so restaurant-side coverage
 * here focuses on promotion_campaigns + promo_banners. The tables still exist
 * in the restaurant DB from V4; the coupon write path itself is exercised in
 * {@code services/order}.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PromotionPostgresIntegrationTest extends AbstractRestaurantPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
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
    void findActiveCampaignsExcludesEndedAndFuture() {
        PromotionCampaign active = new PromotionCampaign();
        active.setName("Active");
        active.setDiscountPct(new BigDecimal("10.00"));
        active.setDiscountPercent(10.0);
        active.setStartsAt(LocalDateTime.now().minusDays(1));
        active.setEndsAt(LocalDateTime.now().plusDays(7));
        campaignRepository.saveAndFlush(active);

        PromotionCampaign ended = new PromotionCampaign();
        ended.setName("Ended");
        ended.setDiscountPct(new BigDecimal("20.00"));
        ended.setDiscountPercent(20.0);
        ended.setStartsAt(LocalDateTime.now().minusDays(30));
        ended.setEndsAt(LocalDateTime.now().minusDays(1));
        campaignRepository.saveAndFlush(ended);

        List<PromotionCampaign> acts = campaignRepository.findActiveCampaigns(LocalDateTime.now());
        assertThat(acts).extracting(PromotionCampaign::getName).containsExactly("Active");
    }
}
