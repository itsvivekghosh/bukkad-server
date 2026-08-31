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
 * Validates the PostgreSQL full-text search queries (native {@code @@} /
 * {@code plainto_tsquery} on tsvector expressions) against a real PostgreSQL
 * instance with the GIN tsvector indexes. These are the queries behind
 * menu/restaurant search; a missing index surfaces here as a sequential scan
 * or error at CI time instead of in production.
 *
 * <p>The tests deliberately run WITHOUT a surrounding transaction
 * ({@code NOT_SUPPORTED}): the fixture INSERTs must commit before the search
 * query runs. Each repository call opens its own short transaction and
 * {@link #cleanTestData()} resets the tables before every test.</p>
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
        // restaurant_cuisines must be cleared before restaurants (FK); also
        // clears rows committed by other tests in the shared container.
        jdbcTemplate.update("DELETE FROM restaurant_cuisines");
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
                INSERT INTO users (id, role, active, email_verified, created_at)
                VALUES
                (1, 'RESTAURANT_OWNER', TRUE, TRUE, CURRENT_TIMESTAMP),
                (2, 'RESTAURANT_OWNER', TRUE, TRUE, CURRENT_TIMESTAMP),
                (3, 'RESTAURANT_OWNER', TRUE, TRUE, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO restaurant_owners (id, verified, email, password, full_name)
                VALUES (1, TRUE, 'ft-owner@test.com', 'x', 'FT Owner'),
                       (2, TRUE, 'ft-owner2@test.com', 'x', 'FT Owner Two'),
                       (3, TRUE, 'ft-owner3@test.com', 'x', 'FT Owner Three')
                """);
        jdbcTemplate.update("""
                INSERT INTO restaurants (id, name, owner_id, opening_time, closing_time, is_active, created_at)
                VALUES
                (1, 'Butter Chicken Palace', 1, '10:00:00', '23:00:00', TRUE, CURRENT_TIMESTAMP),
                (2, 'Green Bowl', 2, '10:00:00', '23:00:00', TRUE, CURRENT_TIMESTAMP),
                (3, 'Tandoori Express', 3, '10:00:00', '23:00:00', TRUE, CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("INSERT INTO menu_categories (id, name, restaurant_id) VALUES (1, 'Mains', 1), (2, 'Sides', 2), (3, 'Desserts', 3)");
        jdbcTemplate.update("""
                INSERT INTO menu_items (id, name, category_id, price, food_type, is_veg, created_at)
                VALUES
                (1, 'Butter Chicken', 1, 320.0, 'NON_VEG', FALSE, CURRENT_TIMESTAMP),
                (2, 'Green Salad', 2, 150.0, 'VEG', TRUE, CURRENT_TIMESTAMP),
                (3, 'Gulab Jamun', 3, 100.0, 'VEG', TRUE, CURRENT_TIMESTAMP)
                """);
    }
}
