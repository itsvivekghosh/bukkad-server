package com.bhukkad.restaurant;

import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import com.bhukkad.restaurant.domain.Restaurant;
import com.bhukkad.restaurant.domain.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the V2 restaurant migration and repositories against a real
 * PostgreSQL database (Plan §10: repository tests on PG per service).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RestaurantRepositoryPostgresIntegrationTest extends AbstractRestaurantPostgresTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM menu_items");
        jdbcTemplate.update("DELETE FROM restaurants");
        jdbcTemplate.update("DELETE FROM cuisines");
        jdbcTemplate.update("INSERT INTO cuisines (name) VALUES ('North Indian')");
    }

    @Test
    void migration_appliedBothV1AndV2() {
        Integer outboxTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('outbox_events','idempotency_records','saga_instances')",
                Integer.class);
        assertThat(outboxTables).isEqualTo(3);

        Integer restaurantTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('restaurants','menu_items','cuisines','restaurant_ratings_summary')",
                Integer.class);
        assertThat(restaurantTables).isEqualTo(4);
    }

    @Test
    void browseByCuisine_returnsActiveOnly() {
        Long cuisineId = jdbcTemplate.queryForObject("SELECT id FROM cuisines LIMIT 1", Long.class);
        Restaurant active = new Restaurant();
        active.setName("Spice Garden");
        active.setCuisineId(cuisineId);
        active.setIsActive(true);
        Restaurant inactive = new Restaurant();
        inactive.setName("Closed Shop");
        inactive.setCuisineId(cuisineId);
        inactive.setIsActive(false);
        restaurantRepository.saveAll(List.of(active, inactive));

        List<Restaurant> result = restaurantRepository.findByCuisineIdAndIsActiveTrue(cuisineId);

        assertThat(result).extracting(Restaurant::getName).containsExactly("Spice Garden");
    }

    @Test
    void searchByName_isCaseInsensitive() {
        Long cuisineId = jdbcTemplate.queryForObject("SELECT id FROM cuisines LIMIT 1", Long.class);
        Restaurant r = new Restaurant();
        r.setName("Pizza Palace");
        r.setCuisineId(cuisineId);
        r.setIsActive(true);
        restaurantRepository.save(r);

        List<Restaurant> result = restaurantRepository.findByNameContainingIgnoreCaseAndIsActiveTrue("pizza");

        assertThat(result).extracting(Restaurant::getName).containsExactly("Pizza Palace");
    }

    @Test
    void menuSnapshot_returnsAvailableItems() {
        Long cuisineId = jdbcTemplate.queryForObject("SELECT id FROM cuisines LIMIT 1", Long.class);
        Restaurant r = new Restaurant();
        r.setName("Spice Garden");
        r.setCuisineId(cuisineId);
        r.setIsActive(true);
        Restaurant saved = restaurantRepository.save(r);

        MenuItem available = new MenuItem();
        available.setRestaurantId(saved.getId());
        available.setName("Paneer");
        available.setPrice(new BigDecimal("240.00"));
        available.setIsAvailable(true);
        MenuItem hidden = new MenuItem();
        hidden.setRestaurantId(saved.getId());
        hidden.setName("Secret");
        hidden.setPrice(new BigDecimal("999.00"));
        hidden.setIsAvailable(false);
        menuItemRepository.saveAll(List.of(available, hidden));

        List<MenuItem> items = menuItemRepository.findByRestaurantIdAndIsAvailableTrue(saved.getId());

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getName()).isEqualTo("Paneer");
    }
}
