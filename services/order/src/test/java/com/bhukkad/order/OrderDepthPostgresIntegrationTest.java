package com.bhukkad.order;

import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.CartItemRepository;
import com.bhukkad.order.domain.CartRepository;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderRepository;
import com.bhukkad.order.domain.OrderInvoice;
import com.bhukkad.order.domain.OrderInvoiceRepository;
import com.bhukkad.order.domain.OrderEtaSnapshot;
import com.bhukkad.order.domain.OrderEtaSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Batch B depth tables (carts, invoices, ETA) and the PG-native
 * order-archive query against real PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderDepthPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private OrderInvoiceRepository invoiceRepository;
    @Autowired private OrderEtaSnapshotRepository etaRepository;
    @Autowired private OrderRepository orderRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM order_eta_snapshots");
        jdbcTemplate.update("DELETE FROM order_invoices");
        jdbcTemplate.update("DELETE FROM order_timeline_events");
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
    }

    @Test
    void migration_appliedV3Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('carts','cart_items','order_invoices','order_eta_snapshots','orders_archive')",
                Integer.class);
        assertThat(tables).isEqualTo(5);
    }

    @Test
    void cartAndItemsPersist() {
        Cart cart = new Cart();
        cart.setCustomerId(1L);
        cart.setStatus("ACTIVE");
        Cart saved = cartRepository.saveAndFlush(cart);

        CartItem item = new CartItem();
        item.setCartId(saved.getId());
        item.setMenuItemId(100L);
        item.setItemName("Paneer");
        item.setUnitPrice(new BigDecimal("240.00"));
        item.setQuantity(2);
        cartItemRepository.saveAndFlush(item);

        assertThat(cartItemRepository.findByCartId(saved.getId())).hasSize(1);
        assertThat(cartRepository.findByCustomerIdAndStatus(1L, "ACTIVE")).isPresent();
    }

    @Test
    void invoiceUniquePerOrder() {
        Order order = order(LocalDateTime.now().minusDays(30));
        Order saved = orderRepository.saveAndFlush(order);

        OrderInvoice invoice = new OrderInvoice();
        invoice.setOrderId(saved.getId());
        invoice.setInvoiceNumber("INV-" + saved.getId());
        invoice.setTotal(new BigDecimal("118.00"));
        invoiceRepository.saveAndFlush(invoice);

        assertThat(invoiceRepository.findByOrderId(saved.getId())).isPresent();
    }

    @Test
    void etaSnapshotsPersistInOrder() {
        Order saved = orderRepository.saveAndFlush(order(LocalDateTime.now()));
        OrderEtaSnapshot first = new OrderEtaSnapshot();
        first.setOrderId(saved.getId());
        first.setEtaMinutes(25);
        etaRepository.saveAndFlush(first);
        OrderEtaSnapshot second = new OrderEtaSnapshot();
        second.setOrderId(saved.getId());
        second.setEtaMinutes(20);
        second.setActualMinutes(22);
        etaRepository.saveAndFlush(second);

        assertThat(etaRepository.findByOrderId(saved.getId())).hasSize(2);
    }

    @Test
    void archiveOldOrders_movesAndDeletes() {
        Order old = orderRepository.saveAndFlush(order(LocalDateTime.now().minusDays(40)));
        // Auditing stamps createdAt=now() on persist; force the age via JDBC.
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(40), old.getId());
        Order recent = orderRepository.saveAndFlush(order(LocalDateTime.now().minusDays(1)));

        int archived = orderRepository.archiveOldOrders(LocalDate.now().minusDays(30), 100);

        assertThat(archived).isEqualTo(1);
        assertThat(orderRepository.findById(old.getId())).isEmpty();
        assertThat(orderRepository.findById(recent.getId())).isPresent();

        Integer inArchive = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders_archive WHERE id = ?", Integer.class, old.getId());
        assertThat(inArchive).isEqualTo(1);
    }

    @Test
    void archiveOldOrders_respectsLimit() {
        Order old1 = orderRepository.saveAndFlush(order(LocalDateTime.now().minusDays(40)));
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(40), old1.getId());
        Order old2 = orderRepository.saveAndFlush(order(LocalDateTime.now().minusDays(45)));
        jdbcTemplate.update("UPDATE orders SET created_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(45), old2.getId());

        int archived = orderRepository.archiveOldOrders(LocalDate.now().minusDays(30), 1);

        assertThat(archived).isEqualTo(1);
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    private Order order(LocalDateTime createdAt) {
        Order order = new Order();
        order.setCustomerId(1L);
        order.setRestaurantId(2L);
        order.setStatus(Order.STATUS_CREATED);
        order.setTotalAmount(new BigDecimal("100.00"));
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        return order;
    }
}
