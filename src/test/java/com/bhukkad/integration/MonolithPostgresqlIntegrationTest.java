package com.bhukkad.integration;

import com.bhukkad.entity.Cart;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Review;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the monolith PostgreSQL baseline
 * ({@code db/migration-pg/V1__monolith_pg_baseline.sql}).
 *
 * <p>Validates that the baseline migrates cleanly and that the core entity
 * write/read paths work on PostgreSQL 16: orders with items, carts with items,
 * and reviews (including the {@code review_images} element collection and the
 * {@code moderation_status} default).</p>
 *
 * <p>Cross-domain references (Customer, Restaurant, Address, MenuCategory) are
 * wired through {@link EntityManager#getReference} lazy proxies: their owning
 * tables are intentionally absent from the minimal baseline, so only the FK
 * column value is persisted — the baseline declares no FK constraints to tables
 * that do not exist yet. Tables that DO exist and are FK-referenced
 * ({@code menu_items} referenced by {@code order_items}/{@code cart_items},
 * and {@code orders} referenced by {@code reviews}) use real saved rows.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class MonolithPostgresqlIntegrationTest extends AbstractPostgresJpaIntegrationTest {

    private static final Long PROXY_CUSTOMER_ID = 101L;
    private static final Long PROXY_RESTAURANT_ID = 201L;
    private static final Long PROXY_ADDRESS_ID = 301L;
    private static final Long PROXY_MENU_CATEGORY_ID = 1L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private MenuItemRepository menuItemRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM review_images");
        jdbcTemplate.update("DELETE FROM reviews");
        jdbcTemplate.update("DELETE FROM order_item_customizations");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM cart_item_customizations");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
        jdbcTemplate.update("DELETE FROM order_invoices");
        jdbcTemplate.update("DELETE FROM order_eta_snapshots");
        jdbcTemplate.update("DELETE FROM order_timeline_events");
        jdbcTemplate.update("DELETE FROM payments");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM menu_item_tags");
        jdbcTemplate.update("DELETE FROM menu_item_allergens");
        jdbcTemplate.update("DELETE FROM menu_item_ingredients");
        jdbcTemplate.update("DELETE FROM menu_item_images");
        jdbcTemplate.update("DELETE FROM menu_items");
    }

    // ------------------------------------------------------------------
    // 1. Baseline migration
    // ------------------------------------------------------------------

    @Test
    void pgBaseline_appliesCleanlyAsV1ThroughV6() {
        // The monolith PG schema is split across versioned migrations:
        // V1 = platform + order/menu/payment core; V2 = remaining domain
        // tables; V3 = V62 role-auth columns; V4 = missing MySQL ALTER-series
        // columns; V5 = missing tables (orders_archive, data_export_requests); V6 = baseline seed data.
        // All must apply cleanly.
        List<Map<String, Object>> history = jdbcTemplate.queryForList(
                "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(history).hasSize(6);
        for (int i = 0; i < 6; i++) {
            assertThat(history.get(i).get("version").toString()).isEqualTo(String.valueOf(i + 1));
            assertThat(history.get(i).get("success")).isEqualTo(true);
        }
    }

    @Test
    void monolithCoreTables_exist() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                        "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'",
                String.class);

        assertThat(tables).contains(
                "outbox_events", "dead_letter_events", "saga_instances", "saga_steps", "idempotency_records",
                "orders", "order_items", "order_item_customizations",
                "order_timeline_events", "order_eta_snapshots", "order_invoices",
                "carts", "cart_items", "cart_item_customizations",
                "reviews", "review_images",
                "menu_items", "payments");
    }

    @Test
    void monolithKeyIndexes_exist() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()",
                String.class);

        assertThat(indexes).contains(
                "idx_order_customer_created",
                "idx_order_restaurant_status_created",
                "idx_order_agent_status",
                "idx_order_status_created",
                "idx_menu_item_category_available",
                "idx_payment_order_status",
                "idx_review_restaurant_moderation_created",
                "idx_outbox_status_created");
    }

    // ------------------------------------------------------------------
    // 2. Order with items
    // ------------------------------------------------------------------

    @Test
    void order_withItems_roundTrips() {
        MenuItem menuItem = saveMenuItem("Paneer Tikka");

        Order order = new Order();
        order.setOrderNumber("PG-ORD-" + System.nanoTime());
        order.setCustomer(entityManager.getReference(com.bhukkad.entity.Customer.class, PROXY_CUSTOMER_ID));
        order.setRestaurant(entityManager.getReference(com.bhukkad.entity.Restaurant.class, PROXY_RESTAURANT_ID));
        order.setDeliveryAddress(entityManager.getReference(com.bhukkad.entity.Address.class, PROXY_ADDRESS_ID));
        order.setStatus(Order.OrderStatus.PLACED);
        order.setSubtotal(250.0);
        order.setDeliveryFee(20.0);
        order.setTotalAmount(270.0);
        order.getOrderItems().add(orderItem(order, menuItem, 2, 125.0));

        Order saved = orderRepository.saveAndFlush(order);
        assertThat(saved.getId()).isNotNull();

        Optional<Order> readBack = orderRepository.findById(saved.getId());
        assertThat(readBack).isPresent();
        assertThat(readBack.get().getOrderNumber()).isEqualTo(order.getOrderNumber());
        assertThat(readBack.get().getStatus()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(readBack.get().getTotalAmount()).isEqualTo(270.0);
        assertThat(readBack.get().getOrderItems()).hasSize(1);
        assertThat(readBack.get().getOrderItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void order_orderCategory_computedFromStatus() {
        MenuItem menuItem = saveMenuItem("Category Ck");

        Order order = new Order();
        order.setOrderNumber("PG-CAT-" + System.nanoTime());
        order.setCustomer(entityManager.getReference(com.bhukkad.entity.Customer.class, PROXY_CUSTOMER_ID));
        order.setRestaurant(entityManager.getReference(com.bhukkad.entity.Restaurant.class, PROXY_RESTAURANT_ID));
        order.setDeliveryAddress(entityManager.getReference(com.bhukkad.entity.Address.class, PROXY_ADDRESS_ID));
        order.setStatus(Order.OrderStatus.CONFIRMED);
        order.setSubtotal(50.0);
        order.setTotalAmount(50.0);
        order.getOrderItems().add(orderItem(order, menuItem, 1, 50.0));

        Order saved = orderRepository.saveAndFlush(order);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, order_category FROM orders WHERE id = ?", saved.getId());
        assertThat(row.get("order_category")).isEqualTo("LIVE");

        jdbcTemplate.update("UPDATE orders SET status = 'DELIVERED' WHERE id = ?", saved.getId());
        row = jdbcTemplate.queryForMap("SELECT order_category FROM orders WHERE id = ?", saved.getId());
        assertThat(row.get("order_category")).isEqualTo("FULFILLED");
    }

    // ------------------------------------------------------------------
    // 3. Cart with items
    // ------------------------------------------------------------------

    @Test
    void cart_withItems_roundTrips() {
        MenuItem menuItem = saveMenuItem("Butter Chicken");

        Cart cart = new Cart();
        cart.setCustomer(entityManager.getReference(com.bhukkad.entity.Customer.class, PROXY_CUSTOMER_ID));
        cart.setRestaurant(entityManager.getReference(com.bhukkad.entity.Restaurant.class, PROXY_RESTAURANT_ID));
        cart.setCouponCode("WELCOME10");
        cart.getCartItems().add(cartItem(cart, menuItem, 2));

        Cart saved = cartRepository.saveAndFlush(cart);
        assertThat(saved.getId()).isNotNull();

        Optional<Cart> readBack = cartRepository.findByCustomerId(PROXY_CUSTOMER_ID);
        assertThat(readBack).isPresent();
        assertThat(readBack.get().getCouponCode()).isEqualTo("WELCOME10");
        assertThat(readBack.get().getCartItems()).hasSize(1);
        assertThat(readBack.get().getCartItems().get(0).getQuantity()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // 4. Review
    // ------------------------------------------------------------------

    @Test
    void review_withImages_roundTrips() {
        MenuItem menuItem = saveMenuItem("Gulab Jamun");

        Order order = new Order();
        order.setOrderNumber("PG-REV-" + System.nanoTime());
        order.setCustomer(entityManager.getReference(com.bhukkad.entity.Customer.class, PROXY_CUSTOMER_ID));
        order.setRestaurant(entityManager.getReference(com.bhukkad.entity.Restaurant.class, PROXY_RESTAURANT_ID));
        order.setDeliveryAddress(entityManager.getReference(com.bhukkad.entity.Address.class, PROXY_ADDRESS_ID));
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setSubtotal(80.0);
        order.setTotalAmount(80.0);
        order.getOrderItems().add(orderItem(order, menuItem, 1, 80.0));
        Order savedOrder = orderRepository.saveAndFlush(order);

        Review review = new Review();
        review.setCustomer(entityManager.getReference(com.bhukkad.entity.Customer.class, PROXY_CUSTOMER_ID));
        review.setRestaurant(entityManager.getReference(com.bhukkad.entity.Restaurant.class, PROXY_RESTAURANT_ID));
        review.setOrder(savedOrder);
        review.setRating(5);
        review.setComment("Delicious!");
        review.getImages().add("https://img.example.com/rev/1.jpg");
        review.getImages().add("https://img.example.com/rev/2.jpg");

        Review saved = reviewRepository.saveAndFlush(review);
        assertThat(saved.getId()).isNotNull();

        Optional<Review> readBack = reviewRepository.findById(saved.getId());
        assertThat(readBack).isPresent();
        assertThat(readBack.get().getRating()).isEqualTo(5);
        assertThat(readBack.get().getComment()).isEqualTo("Delicious!");
        assertThat(readBack.get().getModerationStatus()).isEqualTo(Review.ModerationStatus.APPROVED);
        assertThat(readBack.get().getImages())
                .containsExactlyInAnyOrder(
                        "https://img.example.com/rev/1.jpg",
                        "https://img.example.com/rev/2.jpg");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private MenuItem saveMenuItem(String name) {
        MenuItem menuItem = new MenuItem();
        menuItem.setName(name);
        menuItem.setCategory(entityManager.getReference(
                com.bhukkad.entity.MenuCategory.class, PROXY_MENU_CATEGORY_ID));
        menuItem.setPrice(125.0);
        menuItem.setFoodType(MenuItem.FoodType.VEG);
        menuItem.setAvailable(true);
        menuItem.setIsVeg(true);
        return menuItemRepository.saveAndFlush(menuItem);
    }

    private OrderItem orderItem(Order order, MenuItem menuItem, int quantity, double price) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setMenuItem(menuItem);
        item.setQuantity(quantity);
        item.setPrice(price);
        return item;
    }

    private CartItem cartItem(Cart cart, MenuItem menuItem, int quantity) {
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setMenuItem(menuItem);
        item.setQuantity(quantity);
        return item;
    }
}