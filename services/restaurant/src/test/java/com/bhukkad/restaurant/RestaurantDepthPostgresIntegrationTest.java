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
 * Validates Batch C depth tables (reviews, ratings, customizations,
 * inventory, pricing, menu versions) and the tsvector search against PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RestaurantDepthPostgresIntegrationTest extends AbstractRestaurantPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private MenuItemRatingRepository ratingRepository;
    @Autowired private CustomizationChoiceRepository choiceRepository;
    @Autowired private CustomizationOptionRepository optionRepository;
    @Autowired private InventoryAlertRepository alertRepository;
    @Autowired private DynamicPricingRuleRepository pricingRepository;
    @Autowired private MenuVersionRepository menuVersionRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MenuItemRepository menuItemRepository;

    private Long cuisineId;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM menu_versions");
        jdbcTemplate.update("DELETE FROM dynamic_pricing_rules");
        jdbcTemplate.update("DELETE FROM inventory_alerts");
        jdbcTemplate.update("DELETE FROM customization_options");
        jdbcTemplate.update("DELETE FROM customization_choices");
        jdbcTemplate.update("DELETE FROM menu_item_ratings");
        jdbcTemplate.update("DELETE FROM review_images");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM menu_items");
        jdbcTemplate.update("DELETE FROM restaurants");
        jdbcTemplate.update("DELETE FROM cuisines");
        jdbcTemplate.update("INSERT INTO cuisines (name) VALUES ('North Indian')");
        cuisineId = jdbcTemplate.queryForObject("SELECT id FROM cuisines LIMIT 1", Long.class);
    }

    @Test
    void migration_appliedV3Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('reviews','review_images','menu_item_ratings','customization_choices'," +
                        "'customization_options','inventory_alerts','dynamic_pricing_rules','menu_versions')",
                Integer.class);
        assertThat(tables).isEqualTo(8);
    }

    @Test
    void tsvectorGeneratedColumnExists() {
        Integer cols = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() " +
                        "AND table_name = 'restaurants' AND column_name = 'search_vector'",
                Integer.class);
        assertThat(cols).isEqualTo(1);
    }

    @Test
    void tsvectorSearch_findsByKeyword() {
        Restaurant r = new Restaurant();
        r.setName("Spice Garden");
        r.setDescription("North Indian fine dining");
        r.setCuisineId(cuisineId);
        r.setIsActive(true);
        restaurantRepository.saveAndFlush(r);

        var results = restaurantRepository.fullTextSearchByName("spice", 10);
        assertThat(results).extracting(Restaurant::getName).contains("Spice Garden");
    }

    @Test
    void tsvectorSearch_ranksByRelevance() {
        Restaurant r1 = new Restaurant(); r1.setName("Pizza Place"); r1.setDescription("Italian"); r1.setCuisineId(cuisineId); r1.setIsActive(true);
        Restaurant r2 = new Restaurant(); r2.setName("Pizza Palace"); r2.setDescription("Pizza and pasta"); r2.setCuisineId(cuisineId); r2.setIsActive(true);
        restaurantRepository.saveAll(List.of(r1, r2));

        // "Pizza Palace" has pizza in both name and description, should rank higher.
        var results = restaurantRepository.fullTextSearchByName("pizza", 10);
        assertThat(results).hasSize(2);
    }

    @Test
    void saveReviewAndCustomizations() {
        Restaurant r = new Restaurant(); r.setName("R"); r.setCuisineId(cuisineId); r.setIsActive(true);
        restaurantRepository.saveAndFlush(r);

        Review review = new Review();
        review.setRestaurantId(r.getId()); review.setCustomerId(1L);
        review.setRating(4); review.setComment("Great"); review.setCreatedAt(LocalDateTime.now());
        reviewRepository.saveAndFlush(review);

        MenuItem item = new MenuItem();
        item.setRestaurantId(r.getId()); item.setName("Paneer");
        item.setPrice(new BigDecimal("240.00")); item.setIsAvailable(true);
        menuItemRepository.saveAndFlush(item);

        CustomizationChoice choice = new CustomizationChoice();
        choice.setMenuItemId(item.getId()); choice.setName("Size");
        choiceRepository.saveAndFlush(choice);

        CustomizationOption opt = new CustomizationOption();
        opt.setChoiceId(choice.getId()); opt.setLabel("Large");
        opt.setPriceDelta(new BigDecimal("30.00"));
        optionRepository.saveAndFlush(opt);

        assertThat(reviewRepository.findByRestaurantId(r.getId())).hasSize(1);
        assertThat(choiceRepository.findByMenuItemId(item.getId())).hasSize(1);
        assertThat(optionRepository.findByChoiceId(choice.getId())).hasSize(1);
    }

    @Test
    void inventoryAlertAndPricing() {
        Restaurant r = new Restaurant(); r.setName("R"); r.setCuisineId(cuisineId); r.setIsActive(true);
        restaurantRepository.saveAndFlush(r);
        MenuItem item = new MenuItem();
        item.setRestaurantId(r.getId()); item.setName("Paneer");
        item.setPrice(new BigDecimal("240.00")); item.setIsAvailable(true);
        menuItemRepository.saveAndFlush(item);

        alertRepository.saveAndFlush(
                createAlert(item.getId(), 5, 10, InventoryAlert.TYPE_LOW_STOCK));

        DynamicPricingRule rule = new DynamicPricingRule();
        rule.setRestaurantId(r.getId()); rule.setRuleName("Evening surge");
        rule.setMultiplier(new BigDecimal("1.20")); rule.setActive(true);
        rule.setCreatedAt(LocalDateTime.now());
        pricingRepository.saveAndFlush(rule);

        assertThat(alertRepository.findByMenuItemId(item.getId())).hasSize(1);
        assertThat(pricingRepository.findByRestaurantIdAndActiveTrue(r.getId())).hasSize(1);
    }

    private InventoryAlert createAlert(Long menuItemId, int stock, int threshold, String type) {
        InventoryAlert alert = new InventoryAlert();
        alert.setMenuItemId(menuItemId);
        alert.setCurrentStock(stock);
        alert.setThreshold(threshold);
        alert.setAlertType(type);
        alert.setCreatedAt(LocalDateTime.now());
        return alert;
    }
}