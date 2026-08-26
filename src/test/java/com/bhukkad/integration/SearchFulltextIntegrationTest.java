package com.bhukkad.integration;

import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the MySQL FULLTEXT search queries (native {@code MATCH ... AGAINST})
 * against a real MySQL 8 instance with the V1 FULLTEXT indexes. These are the
 * queries behind menu/restaurant search; a missing FULLTEXT index surfaces here
 * as MySQL error 1191 at CI time instead of in production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SearchFulltextIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM menu_items WHERE id IN (1)");
        jdbcTemplate.update("DELETE FROM menu_categories WHERE id IN (1)");
        jdbcTemplate.update("DELETE FROM restaurants WHERE id IN (1)");
        jdbcTemplate.update("DELETE FROM restaurant_owners WHERE id IN (1)");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (1)");
    }

    @Test
    void restaurantFullTextSearch_matchesByWord() {
        insertFixture();

        List<Restaurant> results = restaurantRepository.fullTextSearchByName("butter");

        assertThat(results).extracting(Restaurant::getName).contains("Butter Chicken Palace");
    }

    @Test
    void menuItemFullTextSearch_matchesByName() {
        insertFixture();

        List<MenuItem> results = menuItemRepository.fullTextSearch("chicken");

        assertThat(results).extracting(MenuItem::getName).contains("Butter Chicken");
    }

    @Test
    void fullTextSearch_noMatch_returnsEmpty() {
        insertFixture();

        assertThat(restaurantRepository.fullTextSearchByName("paneer")).isEmpty();
        assertThat(menuItemRepository.fullTextSearch("paneer")).isEmpty();
    }

    private void insertFixture() {
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password, full_name, role, active, email_verified, created_at)
                VALUES (1, 'ft-owner@test.com', 'x', 'FT Owner', 'RESTAURANT_OWNER', 1, 1, NOW(6))
                """);
        jdbcTemplate.update("INSERT INTO restaurant_owners (id) VALUES (1)");
        jdbcTemplate.update("""
                INSERT INTO restaurants (id, name, owner_id, opening_time, closing_time, is_active, created_at)
                VALUES (1, 'Butter Chicken Palace', 1, '10:00:00', '23:00:00', 1, NOW(6))
                """);
        jdbcTemplate.update("INSERT INTO menu_categories (id, name, restaurant_id) VALUES (1, 'Mains', 1)");
        jdbcTemplate.update("""
                INSERT INTO menu_items (id, name, category_id, price, food_type, is_veg, created_at)
                VALUES (1, 'Butter Chicken', 1, 320.0, 'NON_VEG', 0, NOW(6))
                """);
    }
}
