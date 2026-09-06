package com.bhukkad.order.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.CartItemRepository;
import com.bhukkad.order.domain.CartRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

/**
 * Shopping-cart management (Batch B depth). A customer has at most one active
 * cart; items are snapshots (menuItemId + name + price) so later menu edits do
 * not mutate an in-flight cart.
 *
 * <p>Audit batch A — race-safe add: {@code V4__cart_unique_active.sql} enforces
 * {@code UNIQUE (customer_id) WHERE status='ACTIVE'} and
 * {@code UNIQUE (cart_id, menu_item_id)} in PostgreSQL. Concurrent {@code add}
 * callers used to double-create carts and duplicate lines (select-then-insert
 * with no mutual exclusion). Now the database arbitrates: the loser's mutation
 * fails with a duplicate-key violation, its own (REQUIRES_NEW) transaction is
 * rolled back, and the add is retried once against the winner rows — adopt the
 * winning ACTIVE cart and merge quantity into any winning line. The retry
 * reads in fresh transactions because PostgreSQL aborts a transaction on the
 * first statement error: continuing inside the poisoned transaction (or
 * reading in the caller's snapshot transaction) can never see the rows the
 * winner committed.</p>
 */
@Slf4j
@Service
public class CartService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CHECKED_OUT = "CHECKED_OUT";

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final TransactionTemplate writeTxTemplate;
    private final TransactionTemplate readTxTemplate;

    public CartService(CartRepository cartRepository,
                       CartItemRepository cartItemRepository,
                       PlatformTransactionManager transactionManager) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        // Race-guarded mutations commit in their own transaction so a
        // duplicate-key loss is contained and one retry runs on fresh state.
        this.writeTxTemplate = new TransactionTemplate(transactionManager);
        this.writeTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Post-race winner lookups must read a fresh snapshot: when addItem is
        // nested in a caller transaction (e.g. reorder) REPEATABLE READ would
        // otherwise miss the just-committed winner rows.
        this.readTxTemplate = new TransactionTemplate(transactionManager);
        this.readTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTxTemplate.setReadOnly(true);
    }

    /**
     * Adds (or merges) one menu item into the customer's ACTIVE cart. The
     * cart-creation attempt runs in its own transaction; on a duplicate-key
     * race the ACTIVE cart is refetched once and the item upsert proceeds on
     * the winning row.
     */
    public Cart addItem(Long customerId, Long menuItemId, String name, BigDecimal unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new BusinessException("Quantity must be positive");
        }
        if (menuItemId == null) {
            throw new BusinessException("menuItemId is required");
        }

        Cart cart;
        try {
            cart = writeTxTemplate.execute(status -> findOrCreateActiveCart(customerId));
        } catch (DataIntegrityViolationException loser) {
            // Lost the uq_carts_customer_active race — adopt the winning row.
            log.info("CART_CREATE_RACE | customerId={} | adopting winning active cart", customerId);
            cart = readTxTemplate.execute(status -> requireActiveCart(customerId));
        }
        final Long cartId = cart.getId();

        try {
            writeTxTemplate.executeWithoutResult(status -> upsertItem(cartId, menuItemId, name, unitPrice, quantity));
        } catch (DataIntegrityViolationException loser) {
            // Lost the uq_cart_item_unique race — merge into the winning line.
            log.info("CART_ITEM_RACE | cartId={} | menuItemId={} | merging quantity", cartId, menuItemId);
            writeTxTemplate.executeWithoutResult(status -> {
                CartItem winner = cartItemRepository.findByCartIdAndMenuItemId(cartId, menuItemId)
                        .orElseThrow(() -> new BusinessException(
                                "Cart line vanished while resolving an add race"));
                winner.setQuantity(winner.getQuantity() + quantity);
                cartItemRepository.save(winner);
            });
        }
        return cart;
    }

    private Cart findOrCreateActiveCart(Long customerId) {
        return cartRepository.findByCustomerIdAndStatus(customerId, STATUS_ACTIVE)
                .orElseGet(() -> {
                    Cart c = new Cart();
                    c.setCustomerId(customerId);
                    c.setStatus(STATUS_ACTIVE);
                    return cartRepository.save(c);
                });
    }

    private Cart requireActiveCart(Long customerId) {
        return cartRepository.findByCustomerIdAndStatus(customerId, STATUS_ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Active cart vanished while resolving a create race for customer " + customerId));
    }

    private void upsertItem(Long cartId, Long menuItemId, String name, BigDecimal unitPrice, int quantity) {
        cartItemRepository.findByCartIdAndMenuItemId(cartId, menuItemId)
                .ifPresentOrElse(existing -> {
                    existing.setQuantity(existing.getQuantity() + quantity);
                    cartItemRepository.save(existing);
                }, () -> {
                    CartItem item = new CartItem();
                    item.setCartId(cartId);
                    item.setMenuItemId(menuItemId);
                    item.setItemName(name);
                    item.setUnitPrice(unitPrice);
                    item.setQuantity(quantity);
                    cartItemRepository.save(item);
                });
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

    /** Updates one cart line's quantity (caller must own the active cart). */
    @Transactional
    public void updateQuantity(Long customerId, Long cartItemId, int quantity) {
        CartItem item = ownedItem(customerId, cartItemId);
        item.setQuantity(quantity);
        cartItemRepository.save(item);
    }

    /** Removes one cart line (caller must own the active cart). */
    @Transactional
    public void removeItem(Long customerId, Long cartItemId) {
        CartItem item = ownedItem(customerId, cartItemId);
        cartItemRepository.delete(item);
    }

    private CartItem ownedItem(Long customerId, Long cartItemId) {
        return getItems(customerId).stream()
                .filter(i -> i.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found: " + cartItemId));
    }

    @Transactional
    public BigDecimal subtotal(Long customerId) {
        return getItems(customerId).stream()
                .map(i -> i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
