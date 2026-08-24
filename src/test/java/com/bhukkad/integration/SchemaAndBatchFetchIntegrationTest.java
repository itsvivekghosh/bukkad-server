package com.bhukkad.integration;

import com.bhukkad.entity.Restaurant;
import com.bhukkad.repository.RestaurantRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests that verify Flyway migration integrity and the batch-fetch
 * queries added to kill the N+1 problem.
 *
 * <p>All tests share the same {@code @DataJpaTest} + Testcontainers MySQL
 * context so the Hikari pool is created once. Each test method runs in its own
 * transaction (rollback-after-test) and the {@code @BeforeEach} cleanup prevents
 * id collisions between test methods.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class SchemaAndBatchFetchIntegrationTest extends AbstractJpaIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTestData() {
        jdbcTemplate.update("DELETE FROM restaurant_cuisines WHERE restaurant_id IN (1,2)");
        jdbcTemplate.update("DELETE FROM restaurants WHERE id IN (1,2)");
        jdbcTemplate.update("DELETE FROM cuisines WHERE id IN (1,2)");
        jdbcTemplate.update("DELETE FROM addresses WHERE id IN (1,2)");
        jdbcTemplate.update("DELETE FROM restaurant_owners WHERE id IN (1,2)");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (1,2)");
    }

    // ═══════════════════════════════════════════════════════════════
    // Flyway migration integrity
    // ═══════════════════════════════════════════════════════════════

    @Test
    void allMigrationsApplyAndEveryEntityTableExists() throws Exception {
        Set<String> tables = listTables();

        Set<String> expected = Set.of(
                "addresses", "carts", "cart_items", "cart_item_customizations",
                "cuisines", "customers", "users", "delivery_agents", "delivery_zones",
                "menu_categories", "menu_items", "orders", "order_items",
                "order_item_customizations", "payments", "restaurants",
                "restaurant_owners", "reviews", "coupons", "campaign_usages",
                "city_configs", "fraud_events", "fraud_review_queue", "disputes",
                "affiliate_codes", "affiliate_referrals", "tenants",
                "dead_letter_events", "outbox_events", "idempotency_records",
                "api_keys", "wallet_transactions", "device_tokens",
                "customer_notification_preferences", "support_tickets",
                "promo_banners", "promotion_campaigns", "membership_plans",
                "customer_memberships", "gift_cards", "group_orders",
                "order_timeline_events", "restaurant_settlements",
                "rider_delivery_batches", "rider_delivery_batch_orders",
                "rider_earnings", "rider_location_updates", "settlement_runs",
                "restaurant_ratings_summary", "restaurant_order_stats",
                "favorite_restaurants", "menu_item_ratings", "order_invoices",
                "order_delivery_proofs", "order_eta_snapshots", "dynamic_pricing_rules",
                "inventory_alerts", "zone_surge_rules", "group_order_participants"
        );

        Set<String> missing = new HashSet<>(expected);
        missing.removeAll(tables);
        assertThat(missing)
                .as("Tables referenced by JPA entities that are missing from the migrated schema")
                .isEmpty();
    }

    @Test
    void entityMappingIsConsistentWithSchema() {
        assertThat(entityManager.getMetamodel().getEntities()).isNotEmpty();
    }

    // ═══════════════════════════════════════════════════════════════
    // Batch-fetch query correctness (N+1 regression prevention)
    // ═══════════════════════════════════════════════════════════════

    private void insertRestaurant(long id, String name) {
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password, full_name, role, active, email_verified, created_at)
                VALUES (?, ?, ?, ?, 'RESTAURANT_OWNER', 1, 0, NOW())
                """, id, "owner" + id + "@bhukkad.test", "pw", "Owner " + id);
        jdbcTemplate.update("""
                INSERT INTO restaurant_owners (id, verified)
                VALUES (?, 1)
                """, id);
        jdbcTemplate.update("""
                INSERT INTO addresses (id, address_line1, city, state, pincode, latitude, longitude, is_default)
                VALUES (?, ?, 'Bangalore', 'KA', '560001', 12.97, 77.59, 1)
                """, id, "Addr " + id);
        jdbcTemplate.update("""
                INSERT INTO restaurants
                    (id, name, owner_id, address_id, opening_time, closing_time, is_active, is_open,
                     free_delivery_available, created_at)
                VALUES (?, ?, ?, ?, '09:00:00', '23:00:00', 1, 1, 1, NOW())
                """, id, name, id, id);
        jdbcTemplate.update("""
                INSERT INTO cuisines (id, name, active)
                VALUES (?, ?, 1)
                """, id, "Cuisine " + id);
        jdbcTemplate.update("""
                INSERT INTO restaurant_cuisines (restaurant_id, cuisine_id)
                VALUES (?, ?)
                """, id, id);
    }

    @Test
    void findAllByIdsWithDetails_returnsFullyInitializedEntities() {
        insertRestaurant(1L, "Spice Hub");
        insertRestaurant(2L, "Green Bowl");

        List<Restaurant> result = restaurantRepository.findAllByIdsWithDetails(List.of(1L, 2L));

        assertThat(result).hasSize(2);
        for (Restaurant restaurant : result) {
            assertThat(Hibernate.isInitialized(restaurant.getAddress())).isTrue();
            assertThat(Hibernate.isInitialized(restaurant.getCuisines())).isTrue();
            assertThat(Hibernate.isInitialized(restaurant.getOwner())).isTrue();
            assertThat(restaurant.getAddress()).isNotNull();
            assertThat(restaurant.getAddress().getAddressLine1()).isEqualTo("Addr " + restaurant.getId());
            assertThat(restaurant.getCuisines()).isNotEmpty();
        }
    }

    @Test
    void findAllByIdsWithDetails_returnsOnlyRequestedIds() {
        insertRestaurant(1L, "Only One");

        List<Restaurant> result = restaurantRepository.findAllByIdsWithDetails(List.of(1L, 999L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void findAllByIdsWithDetails_emptyList_returnsEmpty() {
        List<Restaurant> result = restaurantRepository.findAllByIdsWithDetails(List.of());
        assertThat(result).isEmpty();
    }

    private Set<String> listTables() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }
        }
        return tables;
    }
}