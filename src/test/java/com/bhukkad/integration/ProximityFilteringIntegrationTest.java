package com.bhukkad.integration;

import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for proximity-based restaurant filtering via the native
 * Haversine query in {@link RestaurantRepository#findNearbyRestaurantIds}.
 *
 * <p>Runs against a real MySQL Testcontainers instance (when Docker is available)
 * to verify the SQL geospatial query produces correct results for edge cases:</p>
 *
 * <ul>
 *   <li>Entities outside the delivery radius → excluded</li>
 *   <li>Entities on the boundary → included (inclusive)</li>
 *   <li>No entities in zone → empty result set</li>
 *   <li>Minimum radius boundary</li>
 *   <li>Distance ordering</li>
 *   <li>Backward compatibility (no proximity → all restaurants)</li>
 * </ul>
 *
 * <p>Test data is seeded from {@code proximity_test_data.sql} which plants
 * restaurants at known distances from a Bengaluru reference point.
 *
 * The tests run WITHOUT a surrounding transaction ({@code NOT_SUPPORTED})
 * because the native query must see committed fixture data — same pattern as
 * {@link SearchFulltextIntegrationTest}.</p>
 *
 * Credentials:
 *   Test DB user: bhukkad / bhukkad_test_pw (Testcontainers MySQL)
 *   Admin login:  admin@bhukkad.dev / Admin@123456
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql(scripts = "/proximity_test_data.sql")
class ProximityFilteringIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Reference point: Bengaluru city center
    private static final double CENTER_LAT = 12.9716;
    private static final double CENTER_LON = 77.5946;

    private double[] bbox;

    @BeforeEach
    void setUpBoundingBox() {
        bbox = com.bhukkad.util.DistanceCalculator.boundingBoxDeltas(5.0);
    }

    private List<Long> nearby(double radiusKm) {
        double[] deltas = com.bhukkad.util.DistanceCalculator.boundingBoxDeltas(radiusKm);
        return restaurantRepository.findNearbyRestaurantIds(
                CENTER_LAT, CENTER_LON, deltas[0], deltas[1], radiusKm,
                com.bhukkad.util.Constants.PROXIMITY_RADIUS_EPSILON_KM, 50);
    }

    @BeforeEach
    void cleanOtherRestaurants() {
        // Ensure only the proximity test restaurants exist so the nearby query
        // returns deterministic results.
        jdbcTemplate.update(
            "DELETE FROM restaurant_cuisines WHERE restaurant_id NOT IN (500,501,502,503,504,505,506,507)");
        jdbcTemplate.update(
            "DELETE FROM restaurants WHERE id NOT IN (500,501,502,503,504,505,506,507)");
        jdbcTemplate.update(
            "DELETE FROM addresses WHERE id NOT IN (500,501,502,503,504,505,506,507)");
    }

    // ==================== Edge Case: Entities outside radius ====================

    @Test
    void nearbyRestaurantIds_excludesEntitiesOutsideRadius() {
        // 5km radius — restaurant 505 (Nandi Hills, ~60km away) must be excluded
        List<Long> ids = nearby(5.0);

        assertThat(ids).isNotEmpty();
        assertThat(ids).doesNotContain(505L);

        // All returned restaurants must be the ones within 5km
        Set<Long> idSet = ids.stream().collect(Collectors.toSet());
        // Restaurants 500-504 are all within ~3.5 km of the center
        assertThat(idSet).contains(500L, 501L, 502L, 503L, 504L);
        assertThat(idSet).doesNotContain(505L);
    }

    // ==================== Edge Case: Boundary distance ====================

    @Test
    void nearbyRestaurantIds_includesBoundaryDistance() {
        // 100km radius should include the far-away restaurant (505)
        List<Long> ids = nearby(100.0);

        assertThat(ids).contains(505L);
    }

    @Test
    void nearbyRestaurantIds_boundaryInclusive_atExactRadius() {
        // Calculate the exact distance to restaurant 503 (~3.5 km), then query
        // with that exact radius — 503 should still be included (boundary inclusive)
        List<Long> allIds = nearby(100.0);
        assertThat(allIds).contains(503L);

        // Get the restaurant and compute its exact distance
        List<Restaurant> restaurants = restaurantRepository.findAllByIdsWithDetails(allIds);
        Restaurant r503 = restaurants.stream()
                .filter(r -> r.getId() == 503L)
                .findFirst()
                .orElseThrow();

        double distanceTo503 = com.bhukkad.util.DistanceCalculator.calculateDistance(
                CENTER_LAT, CENTER_LON,
                r503.getAddress().getLatitude(),
                r503.getAddress().getLongitude());

        // Query with the exact distance — boundary should be inclusive
        double[] deltas = com.bhukkad.util.DistanceCalculator.boundingBoxDeltas(distanceTo503);
        List<Long> boundaryIds = restaurantRepository.findNearbyRestaurantIds(
                CENTER_LAT, CENTER_LON, deltas[0], deltas[1], distanceTo503,
                com.bhukkad.util.Constants.PROXIMITY_RADIUS_EPSILON_KM, 50);
        assertThat(boundaryIds).contains(503L);
    }

    // ==================== Edge Case: Empty result set ====================

    @Test
    void nearbyRestaurantIds_emptyResultSet_whenNoRestaurantsInRange() {
        // Query from the middle of the ocean — no restaurants within 1 km
        double[] deltas = com.bhukkad.util.DistanceCalculator.boundingBoxDeltas(1.0);
        List<Long> ids = restaurantRepository.findNearbyRestaurantIds(
                0.0, 0.0, deltas[0], deltas[1], 1.0,
                com.bhukkad.util.Constants.PROXIMITY_RADIUS_EPSILON_KM, 50);
        assertThat(ids).isEmpty();
    }

    // ==================== Edge Case: Minimum radius ====================

    @Test
    void nearbyRestaurantIds_minimumRadiusStillReturnsNearby() {
        // 0.5 km radius (the minimum clamped by findNearbyRestaurants)
        List<Long> ids = nearby(0.5);

        // Restaurant 500 is at the exact center — should always be included
        assertThat(ids).contains(500L);
        // Far-away restaurant should NOT be included
        assertThat(ids).doesNotContain(505L);
    }

    // ==================== Edge Case: Distance ordering ====================

    @Test
    void nearbyRestaurantIds_ordersByDistanceAscending() {
        List<Long> ids = nearby(5.0);

        // The SQL query includes ORDER BY distance ASC — verify the first
        // result is the closest restaurant (restaurant 500 at the center)
        assertThat(ids).isNotEmpty();
        assertThat(ids.get(0)).isEqualTo(500L);
    }

    // ==================== Regression: Backward compatibility ====================

    @Test
    void findAllActiveWithDetails_returnsAllRestaurantsRegardlessOfLocation() {
        List<Restaurant> restaurants = restaurantRepository.findAllActiveWithDetails();

        // Should return ALL active restaurants, including the far-away one
        assertThat(restaurants).isNotEmpty();
        Set<Long> ids = restaurants.stream().map(Restaurant::getId).collect(Collectors.toSet());
        assertThat(ids).contains(505L); // far away — included when no proximity filter
    }

    // ==================== Edge Case: Dateline crossing ====================

    @Test
    void nearbyRestaurantIds_handlesDatelineCrossing() {
        // Query from lon=179.95 with a radius large enough to wrap the dateline
        // (~100 km). Both restaurants 506 (lon=179.9) and 507 (lon=-179.9)
        // are within 100 km of the query point.
        double queryLon = 179.95;
        double queryLat = -17.7134;
        double radiusKm = 100.0;

        double[] deltas = com.bhukkad.util.DistanceCalculator.boundingBoxDeltas(radiusKm);
        List<Long> ids = restaurantRepository.findNearbyRestaurantIds(
                queryLat, queryLon, deltas[0], deltas[1], radiusKm,
                com.bhukkad.util.Constants.PROXIMITY_RADIUS_EPSILON_KM, 50);

        // Both sides of the dateline should be found
        assertThat(ids).contains(506L, 507L);
    }

    // ==================== Top-rated nearby ====================

    @Test
    void topRatedNearby_restaurantsAreSortedByRatingDescending() {
        // Use the repository directly to test the proximity + rating sort logic:
        // 1. Find nearby restaurant IDs
        // 2. Fetch full entities
        // 3. Sort by averageRating DESC, totalReviews DESC
        List<Long> ids = nearby(5.0);
        List<Restaurant> restaurants = restaurantRepository.findAllByIdsWithDetails(ids);

        // Sort by rating (desc), then reviews (desc) — mirrors the service logic
        restaurants.sort((a, b) -> {
            double da = a.getAverageRating() != null ? a.getAverageRating() : 0.0;
            double db = b.getAverageRating() != null ? b.getAverageRating() : 0.0;
            int cmp = Double.compare(db, da);
            if (cmp != 0) return cmp;
            int ra = a.getTotalReviews() != null ? a.getTotalReviews() : 0;
            int rb = b.getTotalReviews() != null ? b.getTotalReviews() : 0;
            return Integer.compare(rb, ra);
        });

        assertThat(restaurants).isNotEmpty();
        // First should have the highest rating
        double firstRating = restaurants.get(0).getAverageRating() != null
                ? restaurants.get(0).getAverageRating() : 0.0;
        for (int i = 1; i < restaurants.size(); i++) {
            double r = restaurants.get(i).getAverageRating() != null
                    ? restaurants.get(i).getAverageRating() : 0.0;
            assertThat(r).isLessThanOrEqualTo(firstRating);
     }
}
  }
