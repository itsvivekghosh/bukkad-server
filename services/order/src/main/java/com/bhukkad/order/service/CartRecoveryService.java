package com.bhukkad.order.service;

import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItemRepository;
import com.bhukkad.order.domain.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stale-cart recovery (port of monolith {@code CartRecoveryService}): expires
 * carts untouched past the TTL so they cannot be checked out with stale items.
 */
@Service
@RequiredArgsConstructor
public class CartRecoveryService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    @Transactional
    public int expireStale(int ttlHours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ttlHours);
        List<Cart> stale = cartRepository.findAll().stream()
                .filter(c -> CartService.STATUS_ACTIVE.equals(c.getStatus()))
                .filter(c -> c.getUpdatedAt() == null || c.getUpdatedAt().isBefore(cutoff))
                .toList();
        stale.forEach(cart -> {
            cartItemRepository.deleteByCartId(cart.getId());
            cart.setStatus(CartService.STATUS_CHECKED_OUT);
            cartRepository.save(cart);
        });
        return stale.size();
    }
}