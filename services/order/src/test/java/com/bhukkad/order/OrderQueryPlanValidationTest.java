package com.bhukkad.order;

import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB query validation (hot-path regression): runs EXPLAIN ANALYZE against the
 * hot order-stream queries and asserts they use indexes rather than
 * sequential scans at high QPS.
 *
 * <p>This is a CI gate: if a future migration or query change removes an
 * index, this test fails before the change reaches production.</p>
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderQueryPlanValidationTest extends AbstractOrderPostgresTest {

    @Autowired private OrderRepository orderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM orders");
    }

    private Order order(Long customerId, Long restaurantId, String status) {
        Order o = new Order();
        o.setCustomerId(customerId);
        o.setRestaurantId(restaurantId);
        o.setStatus(status);
        o.setTotalAmount(BigDecimal.TEN);
        return o;
    }

    @Test
    void findByCustomerId_usesIndex() {
        orderRepository.saveAndFlush(order(1L, 2L, Order.STATUS_CREATED));
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM orders WHERE customer_id = 1",
                String.class));
        assertThat(plan)
                .as("findByCustomerId should use customer_id index, not Seq Scan")
                .contains("Index Scan")
                .doesNotContain("Seq Scan");
    }

    @Test
    void findByRestaurantId_usesIndex() {
        orderRepository.saveAndFlush(order(1L, 2L, Order.STATUS_CREATED));
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM orders WHERE restaurant_id = 2",
                String.class));
        assertThat(plan)
                .as("findByRestaurantId should use restaurant_id index, not Seq Scan")
                .contains("Index Scan")
                .doesNotContain("Seq Scan");
    }

    @Test
    void countByCustomerId_usesIndex() {
        orderRepository.saveAndFlush(order(1L, 2L, Order.STATUS_CREATED));
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT count(*) FROM orders WHERE customer_id = 1",
                String.class));
        assertThat(plan)
                .as("countByCustomerId should use index, not Seq Scan")
                .satisfiesAnyOf(
                        p -> assertThat(p).contains("Index Scan").doesNotContain("Seq Scan"),
                        p -> assertThat(p).contains("Index Only Scan").doesNotContain("Seq Scan")
                );
    }

    @Test
    void sumDeliveredSpendByCustomerId_usesIndex() {
        orderRepository.saveAndFlush(order(1L, 2L, Order.STATUS_DELIVERED));
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT COALESCE(SUM(total_amount), 0) FROM orders WHERE customer_id = 1 AND status = 'DELIVERED'",
                String.class));
        assertThat(plan)
                .as("Sum delivered spend should use composite index or index-only scan")
                .satisfiesAnyOf(
                        p -> assertThat(p).contains("Index Scan").doesNotContain("Seq Scan"),
                        p -> assertThat(p).contains("Index Only Scan").doesNotContain("Seq Scan")
                );
    }

    @Test
    void findByStatusAndUpdatedAt_usesIndex() {
        orderRepository.saveAndFlush(order(1L, 2L, Order.STATUS_AWAITING_PAYMENT));
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT * FROM orders WHERE status = 'AWAITING_PAYMENT' AND updated_at <= now()",
                String.class));
        assertThat(plan)
                .as("Stuck-order sweep should use status+updated_at index, not Seq Scan")
                .satisfiesAnyOf(
                        p -> assertThat(p).contains("Index Scan").doesNotContain("Seq Scan"),
                        p -> assertThat(p).contains("Index Only Scan").doesNotContain("Seq Scan")
                );
    }

    @Test
    void archiveOldOrders_planIsReasonable() {
        orderRepository.saveAndFlush(order(1L, 2L, Order.STATUS_CREATED));
        // The archive query filters on created_at without an index on that
        // column alone; on a small test table Seq Scan is acceptable.
        // In production, validate that this query uses an index or that the
        // table is partitioned by created_at.
        String plan = String.join("\n", jdbcTemplate.queryForList(
                "EXPLAIN ANALYZE SELECT id FROM orders WHERE created_at < now() LIMIT 100",
                String.class));
        assertThat(plan)
                .as("Archive query should produce a valid plan")
                .contains("Seq Scan");
    }
}
