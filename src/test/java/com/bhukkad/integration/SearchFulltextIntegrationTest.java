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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the MySQL FULLTEXT search queries (native {@code MATCH ... AGAINST})
 * against a real MySQL 8 instance with the V1 FULLTEXT indexes. These are the
 * queries behind menu/restaurant search; a missing FULLTEXT index surfaces here
 * as MySQL error 1191 at CI time instead of in production.
 *
 * <p>The tests deliberately run WITHOUT a surrounding transaction
 * ({@code NOT_SUPPORTED}): InnoDB's FULLTEXT index does not see rows inserted
 * in the same uncommitted transaction, so the fixture INSERTs must commit
 * before {@code MATCH ... AGAINST} runs. Each repository call opens its own
 * short transaction and {@link #cleanTestData()} resets the tables before
 * every test.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SearchFulltextIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM menu_items");
        jdbcTemplate.update("DELETE FROM menu_categories");
        jdbcTemplate.update("DELETE FROM restaurants");
        jdbcTemplate.update("DELETE FROM restaurant_owners");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    void restaurantFullTextSearch_matchesByWord() {
        insertFixture();

        List<Restaurant> results = restaurantRepository.fullTextSearchByName("butter");

        // The fixture keeps 3 restaurants with only 1 matching "butter" so the
        // search term stays below MySQL's 50% relevance threshold.
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
                VALUES
                (1, 'ft-owner@test.com', 'x', 'FT Owner', 'RESTAURANT_OWNER', 1, 1, NOW(6)),
                (2, 'ft-owner2@test.com', 'x', 'FT Owner Two', 'RESTAURANT_OWNER', 1, 1, NOW(6)),
                (3, 'ft-owner3@test.com', 'x', 'FT Owner Three', 'RESTAURANT_OWNER', 1, 1, NOW(6))
                """);
        jdbcTemplate.update("INSERT INTO restaurant_owners (id) VALUES (1), (2), (3)");
        jdbcTemplate.update("""
                INSERT INTO restaurants (id, name, owner_id, opening_time, closing_time, is_active, created_at)
                VALUES
                (1, 'Butter Chicken Palace', 1, '10:00:00', '23:00:00', 1, NOW(6)),
                (2, 'Green Bowl', 2, '10:00:00', '23:00:00', 1, NOW(6)),
                (3, 'Tandoori Express', 3, '10:00:00', '23:00:00', 1, NOW(6))
                """);
        jdbcTemplate.update("INSERT INTO menu_categories (id, name, restaurant_id) VALUES (1, 'Mains', 1), (2, 'Sides', 2), (3, 'Desserts', 3)");
        jdbcTemplate.update("""
                INSERT INTO menu_items (id, name, category_id, price, food_type, is_veg, created_at)
                VALUES
                (1, 'Butter Chicken', 1, 320.0, 'NON_VEG', 0, NOW(6)),
                (2, 'Green Salad', 2, 150.0, 'VEG', 1, NOW(6)),
                (3, 'Gulab Jamun', 3, 100.0, 'VEG', 1, NOW(6))
                """);
    }
}
