package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.CartItemRepository;
import com.bhukkad.order.domain.CartRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @InjectMocks private CartService service;

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
        existing.setMenuItemId(100L);
        existing.setQuantity(1);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(existing));

        service.addItem(1L, 100L, "Paneer", new BigDecimal("240.00"), 3);

        assertThat(existing.getQuantity()).isEqualTo(4);
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
}
