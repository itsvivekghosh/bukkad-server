package com.bhukkad.order;

import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItem;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderTimelineEvent;
import com.bhukkad.order.domain.OrderTimelineEventRepository;
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
 * Validates the V2 order migration and repositories against PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderTimelineEventRepository timelineRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM order_timeline_events");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
    }

    @Test
    void migration_appliedBothV1AndV2() {
        Integer outboxTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('outbox_events','saga_instances','saga_steps')",
                Integer.class);
        assertThat(outboxTables).isEqualTo(3);

        Integer orderTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('orders','order_items','order_timeline_events')",
                Integer.class);
        assertThat(orderTables).isEqualTo(3);
    }

    @Test
    void saveOrderWithItemsAndTimeline() {
        Order order = new Order();
        order.setCustomerId(1L);
        order.setRestaurantId(2L);
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(new BigDecimal("480.00"));
        Order saved = orderRepository.saveAndFlush(order);

        OrderItem item = new OrderItem();
        item.setOrderId(saved.getId());
        item.setMenuItemId(100L);
        item.setItemName("Paneer");
        item.setUnitPrice(new BigDecimal("240.00"));
        item.setQuantity(2);
        orderItemRepository.saveAndFlush(item);

        OrderTimelineEvent timeline = new OrderTimelineEvent();
        timeline.setOrderId(saved.getId());
        timeline.setEventType("CONFIRMED");
        timelineRepository.saveAndFlush(timeline);

        assertThat(orderRepository.findByCustomerId(1L)).hasSize(1);
        assertThat(orderItemRepository.findByOrderId(saved.getId())).hasSize(1);
        assertThat(timelineRepository.findByOrderId(saved.getId())).hasSize(1);
    }

    @Test
    void deleteOrder_cascadesToItemsAndTimeline() {
        Order order = new Order();
        order.setCustomerId(1L);
        order.setRestaurantId(2L);
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(BigDecimal.TEN);
        Order saved = orderRepository.saveAndFlush(order);

        OrderItem item = new OrderItem();
        item.setOrderId(saved.getId());
        item.setMenuItemId(100L);
        item.setItemName("Paneer");
        item.setUnitPrice(BigDecimal.TEN);
        item.setQuantity(1);
        orderItemRepository.saveAndFlush(item);

        jdbcTemplate.update("DELETE FROM orders WHERE id = ?", saved.getId());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, saved.getId())).isZero();
    }
}
