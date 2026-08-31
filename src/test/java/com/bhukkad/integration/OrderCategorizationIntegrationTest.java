package com.bhukkad.integration;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the V63 order categorization: a MySQL STORED
 * generated column derives {@code order_category} (LIVE / FULFILLED /
 * CANCELLED) from every order's status with zero application-code
 * involvement, and the category-pruned repository finders serve the hot
 * subset paths (live tracking, restaurant queue) from the category-leading
 * indexes.
 *
 * <p>Runs against real MySQL via Testcontainers — the generated-column
 * semantics cannot be exercised on an embedded database.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderCategorizationIntegrationTest extends AbstractJpaIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long customerId;
    private Long restaurantId;
    private Long addressId;

    @BeforeEach
    void seedCustomer() {
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM customers");
        jdbcTemplate.update("DELETE FROM restaurants");
        jdbcTemplate.update("DELETE FROM restaurant_owners");
        jdbcTemplate.update("DELETE FROM users");

        Customer customer = new Customer();
        customer.setEmail("cat@test.local");
        customer.setFullName("Category Test");
        customer.setRole(Customer.UserRole.CUSTOMER);
        customer = customerRepository.saveAndFlush(customer);
        customerId = customer.getId();

        jdbcTemplate.update("""
                INSERT INTO users (role, active, email_verified, phone_verified, profile_completed, created_at)
                VALUES ('RESTAURANT_OWNER', TRUE, FALSE, FALSE, FALSE, CURRENT_TIMESTAMP)
                """);
        Long ownerUserId = jdbcTemplate.queryForObject("SELECT MAX(id) FROM users", Long.class);
        jdbcTemplate.update(
                "INSERT INTO restaurant_owners (id, email, verified) VALUES (?, 'owner@test.local', TRUE)",
                ownerUserId);
        jdbcTemplate.update("""
                INSERT INTO restaurants (name, owner_id, opening_time, closing_time, created_at)
                VALUES ('Category Kitchen', ?, '09:00:00', '23:00:00', CURRENT_TIMESTAMP)
                """, ownerUserId);
        restaurantId = jdbcTemplate.queryForObject(
                "SELECT id FROM restaurants ORDER BY id DESC LIMIT 1", Long.class);

        // orders.delivery_address_id is NOT NULL; seed a delivery address.
        jdbcTemplate.update("""
                INSERT INTO addresses (customer_id, address_line1, city, state, pincode, latitude, longitude, is_default)
                VALUES (?, 'Category Test Addr', 'Bangalore', 'KA', '560001', 12.97, 77.59, TRUE)
                """, customerId);
        addressId = jdbcTemplate.queryForObject(
                "SELECT id FROM addresses WHERE customer_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, customerId);
    }

    private Long insertOrder(String status) {
        jdbcTemplate.update("""
                INSERT INTO orders (order_number, customer_id, restaurant_id, delivery_address_id, status,
                                    subtotal, total_amount, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 100.0, 100.0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, "CAT-" + status + "-" + System.nanoTime(), customerId, restaurantId, addressId, status);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM orders WHERE customer_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, customerId);
    }

    @Test
    void generatedColumn_categorizesEveryRowFromStatus() {
        Long liveId = insertOrder("OUT_FOR_DELIVERY");
        Long fulfilledId = insertOrder("DELIVERED");
        Long cancelledId = insertOrder("CANCELLED");
        Long refundedId = insertOrder("REFUNDED");
        Long placedId = insertOrder("PLACED");

        Map<String, String> byId = jdbcTemplate.query(
                "SELECT id, order_category FROM orders WHERE customer_id = ?",
                rs -> {
                    Map<String, String> m = new java.util.HashMap<>();
                    while (rs.next()) {
                        m.put(String.valueOf(rs.getLong("id")), rs.getString("order_category"));
                    }
                    return m;
                }, customerId);

        assertThat(byId.get(String.valueOf(liveId))).isEqualTo("LIVE");
        assertThat(byId.get(String.valueOf(placedId))).isEqualTo("LIVE");
        assertThat(byId.get(String.valueOf(fulfilledId))).isEqualTo("FULFILLED");
        assertThat(byId.get(String.valueOf(cancelledId))).isEqualTo("CANCELLED");
        assertThat(byId.get(String.valueOf(refundedId))).isEqualTo("CANCELLED");
    }

    @Test
    void categoryFollowsStatusTransitionsWithoutApplicationCode() {
        Long orderId = insertOrder("CONFIRMED");
        assertThat(categoryOf(orderId)).isEqualTo("LIVE");

        jdbcTemplate.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", orderId);
        assertThat(categoryOf(orderId)).isEqualTo("FULFILLED");

        jdbcTemplate.update("UPDATE orders SET status = 'REFUNDED' WHERE id = ?", orderId);
        assertThat(categoryOf(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void customerLiveOrdersFinder_returnsOnlyLiveRows() {
        insertOrder("PLACED");
        insertOrder("OUT_FOR_DELIVERY");
        insertOrder("DELIVERED");
        insertOrder("CANCELLED");

        List<Order> live = orderRepository
                .findByCustomerIdAndOrderCategoryOrderByCreatedAtDesc(customerId, Order.OrderCategory.LIVE);

        assertThat(live).hasSize(2);
        assertThat(live).allSatisfy(o -> assertThat(o.getStatus())
                .isIn(Order.OrderStatus.PLACED, Order.OrderStatus.OUT_FOR_DELIVERY,
                        Order.OrderStatus.CONFIRMED, Order.OrderStatus.PREPARING,
                        Order.OrderStatus.READY_FOR_PICKUP, Order.OrderStatus.SCHEDULED));
    }

    @Test
    void restaurantQueueFinder_prunesHistory() {
        insertOrder("CONFIRMED");
        insertOrder("DELIVERED");
        insertOrder("DELIVERED");

        List<Order> queue = orderRepository
                .findByRestaurantIdAndOrderCategoryOrderByCreatedAtDesc(restaurantId, Order.OrderCategory.LIVE);

        assertThat(queue).hasSize(1);
    }

    @Test
    void indexBackingTheCategoryQueries_exists() {
        Integer indexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT INDEXNAME) FROM pg_indexes
                WHERE TABLENAME = 'orders'
                  AND INDEXNAME IN ('idx_order_customer_category',
                                     'idx_order_restaurant_category',
                                     'idx_order_agent_category',
                                     'idx_order_category_created')
                """, Integer.class);
        assertThat(indexes).isEqualTo(4);
    }

    private String categoryOf(Long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_category FROM orders WHERE id = ?", String.class, orderId);
    }
}
