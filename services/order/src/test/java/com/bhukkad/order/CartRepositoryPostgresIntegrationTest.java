package com.bhukkad.order;

import com.bhukkad.order.domain.entity.Cart;
import com.bhukkad.order.domain.entity.CartItem;
import com.bhukkad.order.domain.repository.CartItemRepository;
import com.bhukkad.order.domain.repository.CartRepository;
import com.bhukkad.order.domain.service.impl.CartService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF-3 (V-carts): validates the sweeper predicate — {@code
 * findIdsByStatusAndUpdatedAtBefore} must select ONLY stale ACTIVE carts,
 * ordered, batch-bounded, purely on the SQL side. Also proves the composite
 * (status, updated_at) index from V8 and the bulk statements used by the
 * sweeper (item delete + status flip).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CartRepositoryPostgresIntegrationTest extends AbstractOrderPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CartRepository cartRepository;
    @Autowired private CartItemRepository cartItemRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM cart_items");
        jdbcTemplate.update("DELETE FROM carts");
    }

    /**
     * JPA auditing (@EnableJpaAuditing on the app) rewrites updatedAt on
     * entity saves, so staleness is stamped via raw SQL — deterministic and
     * auditing-immune, exactly what the sweeper predicate reads.
     */
    private Cart cart(Long customerId, String status, LocalDateTime updatedAt) {
        Cart c = new Cart();
        c.setCustomerId(customerId);
        c.setStatus(status);
        Cart saved = cartRepository.saveAndFlush(c);
        jdbcTemplate.update("UPDATE carts SET updated_at = ?, created_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(updatedAt),
                java.sql.Timestamp.valueOf(updatedAt.minusDays(1)),
                saved.getId());
        return saved;
    }

    @Test
    void v8Migration_addsCompositeSweepIndex() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'carts' AND indexname = 'idx_carts_status_updated_at'
                """, String.class);
        assertThat(columns).hasSize(1);
        assertThat(columns.get(0)).contains("status", "updated_at");
    }

    @Test
    void findIds_selectsOnlyStaleActiveCarts_sqlPredicated() {
        LocalDateTime cutoff = LocalDateTime.now();
        Cart staleActive = cart(1L, CartService.STATUS_ACTIVE, cutoff.minusHours(30));
        cart(2L, CartService.STATUS_ACTIVE, cutoff.minusHours(1));          // fresh → excluded
        cart(3L, CartService.STATUS_CHECKED_OUT, cutoff.minusDays(10));     // non-ACTIVE → excluded

        List<Long> ids = cartRepository.findIdsByStatusAndUpdatedAtBefore(
                CartService.STATUS_ACTIVE, cutoff.minusHours(24), PageRequest.of(0, 500));

        assertThat(ids).containsExactly(staleActive.getId());
    }

    @Test
    void findIds_ordersByIdAndRespectsPageCap() {
        LocalDateTime old = LocalDateTime.now().minusDays(3);
        for (long i = 1; i <= 5; i++) {
            cart(100L + i, CartService.STATUS_ACTIVE, old);
        }

        List<Long> firstPage = cartRepository.findIdsByStatusAndUpdatedAtBefore(
                CartService.STATUS_ACTIVE, LocalDateTime.now(), PageRequest.of(0, 3));
        assertThat(firstPage).hasSize(3);
        assertThat(firstPage).isSorted(); // ORDER BY c.id in SQL
    }

    @Test
    void sweepStatements_deleteLinesAndFlipStatus_inOneStatementPair() {
        LocalDateTime old = LocalDateTime.now().minusDays(3);
        Cart a = cart(1L, CartService.STATUS_ACTIVE, old);
        Cart b = cart(2L, CartService.STATUS_ACTIVE, old);
        CartItem line = new CartItem();
        line.setCartId(a.getId());
        line.setMenuItemId(10L);
        line.setItemName("Dal");
        line.setUnitPrice(new BigDecimal("120.00"));
        line.setQuantity(1);
        cartItemRepository.saveAndFlush(line);

        List<Long> ids = List.of(a.getId(), b.getId());
        assertThat(cartItemRepository.deleteByCartIdIn(ids)).isEqualTo(1);
        assertThat(cartRepository.updateStatusByIds(
                ids, CartService.STATUS_CHECKED_OUT, LocalDateTime.now())).isEqualTo(2);

        assertThat(cartRepository.findIdsByStatusAndUpdatedAtBefore(
                CartService.STATUS_ACTIVE, LocalDateTime.now(), PageRequest.of(0, 500)))
                .isEmpty();
        assertThat(cartItemRepository.findByCartId(a.getId())).isEmpty();
    }
}
