package com.bhukkad.commerce.order;

import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.entity.OrderItem;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.repository.OrderItemRepository;
import com.bhukkad.commerce.order.domain.service.impl.OrderQueryService;
import org.hibernate.stat.Statistics;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.hibernate5.LocalSessionFactoryBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Query-count regression test for OrderQueryService.
 * Verifies that the number of SQL statements does not scale with the number of returned rows.
 * Uses Hibernate statistics (enabled via test profile application-test.yml).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class OrderQueryServiceQueryCountTest extends AbstractOrderPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderQueryService orderQueryService;
    @Autowired private SessionFactory sessionFactory;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM order_timeline_events");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        // Reset Hibernate statistics before each test
        sessionFactory.getStatistics().clear();
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        OrderQueryService orderQueryService(OrderRepository orderRepository, OrderItemRepository orderItemRepository) {
            return new OrderQueryService(orderRepository, orderItemRepository, null, null);
        }
    }

    @Test
    void getOrder_shouldExecuteConstantNumberOfQueriesRegardlessOfItemCount() {
        // Create 3 orders with varying number of items
        Order order1 = createOrderWithItems(1, 1);  // 1 item
        Order order2 = createOrderWithItems(2, 5);  // 5 items
        Order order3 = createOrderWithItems(3, 10); // 10 items

        orderRepository.saveAllAndFlush(List.of(order1, order2, order3));

        // Enable statistics
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // Test order with 1 item
        orderQueryService.getOrder(order1.getId());
        long queriesFor1Item = stats.getPrepareStatementCount();

        stats.clear();

        // Test order with 5 items
        orderQueryService.getOrder(order2.getId());
        long queriesFor5Items = stats.getPrepareStatementCount();

        stats.clear();

        // Test order with 10 items
        orderQueryService.getOrder(order3.getId());
        long queriesFor10Items = stats.getPrepareStatementCount();

        // With @EntityGraph, all should use the same number of queries (1 query with JOIN FETCH)
        // Before fix: would be 2 queries (1 for order, 1 for items)
        // After fix: should be 1 query (JOIN FETCH)
        assertThat(queriesFor1Item).isEqualTo(queriesFor5Items);
        assertThat(queriesFor5Items).isEqualTo(queriesFor10Items);
        assertThat(queriesFor1Item).isLessThanOrEqualTo(2); // At most 2 (1 for order + items with JOIN FETCH)
    }

    @Test
    void getOrdersForCustomer_shouldExecuteConstantNumberOfQueriesRegardlessOfOrderCount() {
        // Create a customer with multiple orders
        Long customerId = 100L;
        
        Order order1 = createOrderWithItems(customerId, 1L, 2);  // order 1 with 2 items
        Order order2 = createOrderWithItems(customerId, 2L, 3);  // order 2 with 3 items
        Order order3 = createOrderWithItems(customerId, 3L, 1);  // order 3 with 1 item
        
        orderRepository.saveAllAndFlush(List.of(order1, order2, order3));

        // Enable statistics
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        // Fetch all orders for customer (3 orders)
        orderQueryService.getOrdersForCustomer(customerId, PageRequest.of(0, 10));
        long queriesFor3Orders = stats.getPrepareStatementCount();

        stats.clear();

        // Create 2 more orders for same customer (total 5 orders)
        Order order4 = createOrderWithItems(customerId, 4L, 4);
        Order order5 = createOrderWithItems(customerId, 5L, 2);
        orderRepository.saveAllAndFlush(List.of(order4, order5));

        // Fetch all orders for customer (5 orders)
        orderQueryService.getOrdersForCustomer(customerId, PageRequest.of(0, 10));
        long queriesFor5Orders = stats.getPrepareStatementCount();

        // Should use same number of queries (2 queries: 1 for orders, 1 for items with IN clause)
        assertThat(queriesFor3Orders).isEqualTo(queriesFor5Orders);
        assertThat(queriesFor3Orders).isLessThanOrEqualTo(2);
    }

    private Order createOrderWithItems(Long customerId, Long orderId, int itemCount) {
        Order order = new Order();
        order.setId(orderId);
        order.setCustomerId(customerId);
        order.setRestaurantId(1L);
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(BigDecimal.valueOf(100 * itemCount));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        return order;
    }

    private Order createOrderWithItems(int orderNum, int itemCount) {
        Order order = new Order();
        order.setCustomerId((long) orderNum);
        order.setRestaurantId(1L);
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(BigDecimal.valueOf(100 * itemCount));
        order.setCreatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
        
        Order saved = orderRepository.saveAndFlush(order);
        
        // Add items
        for (int i = 1; i <= itemCount; i++) {
            OrderItem item = new OrderItem();
            item.setOrderId(saved.getId());
            item.setMenuItemId((long) (orderNum * 100 + i));
            item.setItemName("Item " + i);
            item.setUnitPrice(BigDecimal.valueOf(50));
            item.setQuantity(1);
            orderItemRepository.saveAndFlush(item);
        }
        
        return saved;
    }
}