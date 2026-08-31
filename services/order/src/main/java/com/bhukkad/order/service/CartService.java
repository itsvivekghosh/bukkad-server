package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.CartItemRepository;
import com.bhukkad.order.domain.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shopping-cart management (Batch B depth). A customer has at most one active
 * cart; items are snapshots (menuItemId + name + price) so later menu edits do
 * not mutate an in-flight cart.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CHECKED_OUT = "CHECKED_OUT";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;

    @Transactional
    public Cart addItem(Long customerId, Long menuItemId, String name, BigDecimal unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("Quantity must be positive");
        }
        Cart cart = cartRepository.findByCustomerIdAndStatus(customerId, STATUS_ACTIVE)
                .orElseGet(() -> {
                    Cart c = new Cart();
                    c.setCustomerId(customerId);
                    c.setStatus(STATUS_ACTIVE);
                    return cartRepository.save(c);
                });

        // If the item is already in the cart, bump the quantity instead of
        // adding a duplicate line.
        cartItemRepository.findByCartId(cart.getId()).stream()
                .filter(i -> i.getMenuItemId().equals(menuItemId))
                .findFirst()
                .ifPresentOrElse(existing -> {
                    existing.setQuantity(existing.getQuantity() + quantity);
                    cartItemRepository.save(existing);
                }, () -> {
                    CartItem item = new CartItem();
                    item.setCartId(cart.getId());
                    item.setMenuItemId(menuItemId);
                    item.setItemName(name);
                    item.setUnitPrice(unitPrice);
                    item.setQuantity(quantity);
                    cartItemRepository.save(item);
                });
        return cart;
    }

    @Transactional(readOnly = true)
    public List<CartItem> getItems(Long customerId) {
        return cartRepository.findByCustomerIdAndStatus(customerId, STATUS_ACTIVE)
                .map(cart -> cartItemRepository.findByCartId(cart.getId()))
                .orElse(List.of());
    }

    @Transactional
    public void clear(Long customerId) {
        cartRepository.findByCustomerIdAndStatus(customerId, STATUS_ACTIVE)
                .ifPresent(cart -> {
                    cartItemRepository.deleteByCartId(cart.getId());
                    cart.setStatus(STATUS_CHECKED_OUT);
                    cartRepository.save(cart);
                });
    }

    @Transactional
    public BigDecimal subtotal(Long customerId) {
        return getItems(customerId).stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}