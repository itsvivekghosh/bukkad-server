package com.bhukkad.catalog.restaurant;

import com.bhukkad.catalog.restaurant.domain.entity.MenuItem;
import com.bhukkad.catalog.restaurant.domain.entity.Restaurant;
import com.bhukkad.catalog.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.catalog.restaurant.domain.repository.RestaurantRepository;
import com.bhukkad.catalog.restaurant.domain.service.impl.RestaurantQueryService;
import org.hibernate.stat.Statistics;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count regression test for RestaurantQueryService.
 * Verifies that the number of SQL statements does not scale with the number of restaurants.
 * Uses Hibernate statistics (enabled via test profile application-test.yml).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RestaurantQueryServiceQueryCountTest extends AbstractRestaurantPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MenuItemRepository menuItemRepository;
    @Autowired private RestaurantQueryService restaurantQueryService;
    @Autowired private SessionFactory sessionFactory;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM menu_items");
        jdbcTemplate.update("DELETE FROM restaurants");
        // Reset Hibernate statistics before each test
        sessionFactory.getStatistics().clear();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        RestaurantQueryService restaurantQueryService(RestaurantRepository restaurantRepository, MenuItemRepository menuItemRepository) {
            return new RestaurantQueryService(restaurantRepository, menuItemRepository, null, null);
        }
    }

    @Test
    void preWarmMenuCache_shouldExecuteConstantNumberOfQueriesRegardlessOfRestaurantCount() {
        // Create restaurants with varying number of menu items
        Restaurant r1 = createRestaurantWithItems(1, 1);  // 1 item
        Restaurant r2 = createRestaurantWithItems(2, 5);  // 5 items
        Restaurant r3 = createRestaurantWithItems(3, 10); // 10 items

        restaurantRepository.saveAllAndFlush(List.of(r1, r2, r3));

        // Enable statistics
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // Pre-warm cache for 3 restaurants
        restaurantQueryService.preWarmMenuCache();
        long queriesFor3Restaurants = stats.getPrepareStatementCount();

        stats.clear();

        // Create 2 more restaurants (total 5)
        Restaurant r4 = createRestaurantWithItems(4, 3);
        Restaurant r5 = createRestaurantWithItems(5, 7);
        restaurantRepository.saveAllAndFlush(List.of(r4, r5));

        // Pre-warm cache for 5 restaurants
        restaurantQueryService.preWarmMenuCache();
        long queriesFor5Restaurants = stats.getPrepareStatementCount();

        // With batch query, should use same number of queries (2 queries: 1 for restaurants, 1 for all menu items)
        // Before fix: would be 1 + N queries (1 for restaurants, N for each restaurant's menu items)
        // After fix: should be 2 queries (1 for restaurants, 1 for all menu items with IN clause)
        assertThat(queriesFor3Restaurants).isEqualTo(queriesFor5Restaurants);
        assertThat(queriesFor3Restaurants).isLessThanOrEqualTo(2);
    }

    @Test
    void menuSnapshot_shouldExecuteConstantNumberOfQueriesRegardlessOfItemCount() {
        // Create restaurants with varying number of menu items
        Restaurant r1 = createRestaurantWithItems(1, 1);  // 1 item
        Restaurant r2 = createRestaurantWithItems(2, 5);  // 5 items
        Restaurant r3 = createRestaurantWithItems(3, 10); // 10 items

        restaurantRepository.saveAllAndFlush(List.of(r1, r2, r3));

        // Enable statistics
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // Test restaurant with 1 item
        restaurantQueryService.menuSnapshot(r1.getId());
        long queriesFor1Item = stats.getPrepareStatementCount();

        stats.clear();

        // Test restaurant with 5 items
        restaurantQueryService.menuSnapshot(r2.getId());
        long queriesFor5Items = stats.getPrepareStatementCount();

        stats.clear();

        // Test restaurant with 10 items
        restaurantQueryService.menuSnapshot(r3.getId());
        long queriesFor10Items = stats.getPrepareStatementCount();

        // Should use same number of queries (2 queries: 1 for restaurant, 1 for menu items)
        assertThat(queriesFor1Item).isEqualTo(queriesFor5Items);
        assertThat(queriesFor5Items).isEqualTo(queriesFor10Items);
        assertThat(queriesFor1Item).isLessThanOrEqualTo(2);
    }

    private Restaurant createRestaurantWithItems(int restaurantNum, int itemCount) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId((long) restaurantNum);
        restaurant.setName("Restaurant " + restaurantNum);
        restaurant.setDescription("Description " + restaurantNum);
        restaurant.setCuisineId(1L);
        restaurant.setAddress("Address " + restaurantNum);
        restaurant.setPhone("1234567890");
        restaurant.setIsActive(true);
        restaurant.setIsOpen(true);
        restaurant.setAvgRating(4.5);
        
        Restaurant saved = restaurantRepository.saveAndFlush(restaurant);
        
        // Add menu items
        for (int i = 1; i <= itemCount; i++) {
            MenuItem item = new MenuItem();
            item.setRestaurantId(saved.getId());
            item.setName("Item " + i);
            item.setDescription("Description " + i);
            item.setPrice(BigDecimal.valueOf(100 + i * 10));
            item.setCategoryId(1L);
            item.setIsAvailable(true);
            item.setFoodType(MenuItem.FoodType.VEG);
            item.setIsVeg(true);
            item.setIsSpicy(false);
            item.setSpiceLevel(MenuItem.SpiceLevel.MILD);
            item.setPreparationTime(15);
            item.setBestseller(false);
            item.setRecommended(false);
            item.setAverageRating(4.0);
            item.setTotalRatings(10);
            menuItemRepository.saveAndFlush(item);
        }
        
        return saved;
    }
}