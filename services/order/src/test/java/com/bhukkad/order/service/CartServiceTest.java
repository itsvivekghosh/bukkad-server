package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.CartItemRepository;
import com.bhukkad.order.domain.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartServiceTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;

    private CartService service;

    /** No-op manager: the unit tests exercise the retry control flow, not real commits. */
    private static final class InMemoryTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, TransactionDefinition def) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    @BeforeEach
    void setUp() {
        service = new CartService(cartRepository, cartItemRepository, new InMemoryTxManager());
    }

    private Cart activeCart(Long id) {
        Cart cart = new Cart();
        cart.setId(id);
        cart.setCustomerId(1L);
        cart.setStatus(CartService.STATUS_ACTIVE);
        return cart;
    }

    @Test
    void addItem_createsCartWhenMissing() {
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> {
            Cart c = inv.getArgument(0);
            c.setId(5L);
            return c;
        });

        Cart cart = service.addItem(1L, 100L, "Paneer", new BigDecimal("240.00"), 2);

        assertThat(cart.getId()).isEqualTo(5L);
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void addItem_existingItem_incrementsQuantity() {
        Cart cart = activeCart(5L);
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE)).thenReturn(Optional.of(cart));
        CartItem existing = new CartItem();
        existing.setCartId(5L);
        existing.setMenuItemId(100L);
        existing.setQuantity(1);
        when(cartItemRepository.findByCartIdAndMenuItemId(5L, 100L)).thenReturn(Optional.of(existing));

        service.addItem(1L, 100L, "Paneer", new BigDecimal("240.00"), 3);

        assertThat(existing.getQuantity()).isEqualTo(4);
        verify(cartItemRepository).save(existing);
    }

    @Test
    void addItem_raceOnCartCreate_adoptsWinningActiveCart() {
        // Two concurrent adds: uq_carts_customer_active lets exactly one cart
        // win. The loser's REQUIRES_NEW insert transaction dies with a
        // duplicate key and must fall back to refetching the winner row — not
        // crash and not double-create.
        Cart winner = activeCart(9L);
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE))
                .thenReturn(Optional.empty());      // 1st pass: loser starts its own cart
        when(cartRepository.save(any(Cart.class))).thenThrow(new DuplicateKeyException(
                "duplicate key value violates unique constraint \"uq_carts_customer_active\""));

        Cart secondLook = activeCart(9L);
        // retry pass sees the winner row (fresh transaction reads committed state)
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE))
                .thenReturn(Optional.empty(), Optional.of(secondLook));

        Cart cart = service.addItem(1L, 100L, "Paneer", new BigDecimal("240.00"), 1);

        assertThat(cart.getId()).isEqualTo(9L);
        verify(cartRepository).save(any(Cart.class));   // loser attempted exactly once
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void addItem_raceOnItemLine_mergesQuantityIntoWinningRow() {
        // uq_cart_item_unique: concurrent adds of the same menu item — loser's
        // INSERT conflicts, retry merges the quantity into the winning line.
        Cart cart = activeCart(5L);
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE)).thenReturn(Optional.of(cart));

        CartItem winnerLine = new CartItem();
        winnerLine.setId(77L);
        winnerLine.setCartId(5L);
        winnerLine.setMenuItemId(100L);
        winnerLine.setQuantity(2);

        when(cartItemRepository.findByCartIdAndMenuItemId(5L, 100L))
                .thenReturn(Optional.empty())          // loser looked first
                .thenReturn(Optional.of(winnerLine));  // loser's post-race re-read
        when(cartItemRepository.save(any(CartItem.class))).thenAnswer(inv -> {
            CartItem arg = inv.getArgument(0);
            if (winnerLine == arg) {
                return arg;
            }
            if (arg.getId() == null) {
                throw new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"uq_cart_item_unique\"");
            }
            return arg;
        });

        service.addItem(1L, 100L, "Paneer", new BigDecimal("240.00"), 3);

        assertThat(winnerLine.getQuantity()).isEqualTo(5);
        verify(cartItemRepository, times(2)).findByCartIdAndMenuItemId(5L, 100L);
    }

    @Test
    void addItem_zeroQuantity_throws() {
        assertThatThrownBy(() -> service.addItem(1L, 100L, "X", BigDecimal.ONE, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void subtotal_sumsLineTotals() {
        Cart cart = activeCart(5L);
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE)).thenReturn(Optional.of(cart));
        CartItem a = new CartItem();
        a.setUnitPrice(new BigDecimal("100.00"));
        a.setQuantity(2);
        CartItem b = new CartItem();
        b.setUnitPrice(new BigDecimal("50.00"));
        b.setQuantity(1);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(a, b));

        assertThat(service.subtotal(1L)).isEqualByComparingTo("250.00");
    }

    @Test
    void clear_marksCheckedOut() {
        Cart cart = activeCart(5L);
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE)).thenReturn(Optional.of(cart));

        service.clear(1L);

        assertThat(cart.getStatus()).isEqualTo(CartService.STATUS_CHECKED_OUT);
        verify(cartItemRepository).deleteByCartId(5L);
    }

    @Test
    void addItem_raceSurvivesButRetryConflictPropagates() {
        // Pathological: the retry also loses (third writer). Propagate rather
        // than loop forever.
        when(cartRepository.findByCustomerIdAndStatus(1L, CartService.STATUS_ACTIVE)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenThrow(new DuplicateKeyException("uq_carts_customer_active"));

        // retry's fresh read finds no ACTIVE cart either -> explicit error
        assertThatThrownBy(() -> service.addItem(1L, 100L, "Paneer", new BigDecimal("1.00"), 1))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("vanished");
        verify(cartRepository, times(1)).save(any(Cart.class)); // loser attempted exactly once, no retry loop
    }
}
