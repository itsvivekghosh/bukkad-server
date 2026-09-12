package com.bhukkad.restaurant;

import com.bhukkad.restaurant.domain.entity.CampaignUsage;
import com.bhukkad.restaurant.domain.repository.CampaignUsageRepository;
import com.bhukkad.restaurant.domain.entity.MenuCategory;
import com.bhukkad.restaurant.domain.repository.MenuCategoryRepository;
import com.bhukkad.restaurant.domain.entity.MenuItem;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.restaurant.domain.entity.PromotionCampaign;
import com.bhukkad.restaurant.domain.repository.PromotionCampaignRepository;
import com.bhukkad.restaurant.domain.entity.Restaurant;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
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
 * Validates the V7 menu-category / campaign-usage migration and repositories
 * against a real PostgreSQL database (Plan §10: repository tests on PG per
 * service).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MenuCategoryCampaignUsageRepositoryPostgresIntegrationTest extends AbstractRestaurantPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MenuCategoryRepository menuCategoryRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @Autowired
    private CampaignUsageRepository campaignUsageRepository;

    @Autowired
    private PromotionCampaignRepository promotionCampaignRepository;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM menu_items");
        jdbcTemplate.update("DELETE FROM menu_categories");
        jdbcTemplate.update("DELETE FROM campaign_usages");
        jdbcTemplate.update("DELETE FROM promotion_campaigns");
        jdbcTemplate.update("DELETE FROM restaurants");
        jdbcTemplate.update("DELETE FROM cuisines");
        jdbcTemplate.update("INSERT INTO cuisines (name) VALUES ('North Indian')");
    }

    private Restaurant restaurant(String name) {
        Long cuisineId = jdbcTemplate.queryForObject("SELECT id FROM cuisines LIMIT 1", Long.class);
        Restaurant r = new Restaurant();
        r.setName(name);
        r.setCuisineId(cuisineId);
        r.setIsActive(true);
        return restaurantRepository.save(r);
    }

    @Test
    void migration_appliedV7() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('menu_categories','campaign_usages')",
                Integer.class);
        assertThat(tables).isEqualTo(2);

        Integer categoryColumn = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() " +
                        "AND table_name = 'menu_items' AND column_name = 'category_id'",
                Integer.class);
        assertThat(categoryColumn).isEqualTo(1);
    }

    @Test
    void menuCategories_listOrderedAndFilterActive() {
        Restaurant r = restaurant("Spice Garden");

        MenuCategory mains = new MenuCategory();
        mains.setRestaurantId(r.getId());
        mains.setName("Mains");
        mains.setDisplayOrder(2);
        MenuCategory starters = new MenuCategory();
        starters.setRestaurantId(r.getId());
        starters.setName("Starters");
        starters.setDisplayOrder(1);
        MenuCategory hidden = new MenuCategory();
        hidden.setRestaurantId(r.getId());
        hidden.setName("Hidden");
        hidden.setDisplayOrder(0);
        hidden.setActive(false);
        menuCategoryRepository.saveAll(List.of(mains, starters, hidden));

        List<MenuCategory> ordered = menuCategoryRepository
                .findByRestaurantIdOrderByDisplayOrderAsc(r.getId());
        assertThat(ordered).extracting(MenuCategory::getName)
                .containsExactly("Hidden", "Starters", "Mains");

        List<MenuCategory> active = menuCategoryRepository
                .findByRestaurantIdAndActiveTrue(r.getId());
        assertThat(active).extracting(MenuCategory::getName)
                .containsExactly("Starters", "Mains");
    }

    @Test
    void menuItems_linkToCategoryAndCount() {
        Restaurant r = restaurant("Spice Garden");
        MenuCategory mains = new MenuCategory();
        mains.setRestaurantId(r.getId());
        mains.setName("Mains");
        mains = menuCategoryRepository.save(mains);

        MenuItem paneer = new MenuItem();
        paneer.setRestaurantId(r.getId());
        paneer.setName("Paneer");
        paneer.setPrice(new BigDecimal("240.00"));
        paneer.setCategoryId(mains.getId());
        MenuItem naan = new MenuItem();
        naan.setRestaurantId(r.getId());
        naan.setName("Naan");
        naan.setPrice(new BigDecimal("60.00"));
        naan.setCategoryId(mains.getId());
        MenuItem other = new MenuItem();
        other.setRestaurantId(r.getId());
        other.setName("Soda");
        other.setPrice(new BigDecimal("40.00"));
        menuItemRepository.saveAll(List.of(paneer, naan, other));

        assertThat(menuItemRepository.countByCategoryId(mains.getId())).isEqualTo(2);
        assertThat(menuItemRepository.findByCategoryId(mains.getId()))
                .extracting(MenuItem::getName).containsExactlyInAnyOrder("Paneer", "Naan");
    }

    @Test
    void campaignUsages_countPerCustomerAndAuditStamp() {
        Restaurant r = restaurant("Spice Garden");

        PromotionCampaign campaign = new PromotionCampaign();
        campaign.setName("Diwali 20% off");
        campaign.setDiscountPct(new BigDecimal("20.00"));
        campaign.setStartsAt(LocalDateTime.now().minusDays(1));
        campaign.setEndsAt(LocalDateTime.now().plusDays(7));
        campaign = promotionCampaignRepository.save(campaign);

        CampaignUsage first = new CampaignUsage();
        first.setCampaignId(campaign.getId());
        first.setCustomerId(101L);
        first.setOrderId(9001L);
        CampaignUsage second = new CampaignUsage();
        second.setCampaignId(campaign.getId());
        second.setCustomerId(101L);
        second.setOrderId(9002L);
        CampaignUsage otherCustomer = new CampaignUsage();
        otherCustomer.setCampaignId(campaign.getId());
        otherCustomer.setCustomerId(202L);
        campaignUsageRepository.saveAll(List.of(first, second, otherCustomer));

        assertThat(campaignUsageRepository.countByCampaignId(campaign.getId())).isEqualTo(3);
        assertThat(campaignUsageRepository.countByCampaignIdAndCustomerId(campaign.getId(), 101L))
                .isEqualTo(2);
        assertThat(campaignUsageRepository.countByCampaignIdAndCustomerId(campaign.getId(), 202L))
                .isEqualTo(1);

        List<CampaignUsage> all = campaignUsageRepository.findAll();
        assertThat(all).allSatisfy(u -> assertThat(u.getUsedAt()).isNotNull());
    }
}
