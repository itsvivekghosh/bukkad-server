package com.bhukkad.order.service;

import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartRepository;
import com.bhukkad.order.domain.CartItemRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartRecoveryServiceTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @InjectMocks private CartRecoveryService service;

    private Cart cart(LocalDateTime updatedAt, String status) {
        Cart c = new Cart();
        c.setId(1L);
        c.setStatus(status);
        c.setUpdatedAt(updatedAt);
        return c;
    }

    @Test
    void expireStale_expiresOnlyActiveStaleCarts() {
        Cart stale = cart(LocalDateTime.now().minusDays(2), CartService.STATUS_ACTIVE);
        Cart fresh = cart(LocalDateTime.now(), CartService.STATUS_ACTIVE);
        Cart checkedOut = cart(LocalDateTime.now().minusDays(5), CartService.STATUS_CHECKED_OUT);
        when(cartRepository.findAll()).thenReturn(List.of(stale, fresh, checkedOut));

        int expired = service.expireStale(24);

        assertThat(expired).isEqualTo(1);
        assertThat(stale.getStatus()).isEqualTo(CartService.STATUS_CHECKED_OUT);
        verify(cartItemRepository).deleteByCartId(1L);
        verify(cartRepository).save(any(Cart.class));
    }

    @Test
    void expireStale_zeroWhenNothingStale() {
        when(cartRepository.findAll()).thenReturn(List.of(cart(LocalDateTime.now(), CartService.STATUS_ACTIVE)));

        int expired = service.expireStale(24);

        assertThat(expired).isZero();
    }
}
