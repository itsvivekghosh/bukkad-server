package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.security.PrincipalGuard;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.Cart;
import com.bhukkad.order.domain.CartItem;
import com.bhukkad.order.domain.OrderInvoice;
import com.bhukkad.order.service.CartService;
import com.bhukkad.order.service.OrderInvoiceService;
import com.bhukkad.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Cart + invoice + ETA APIs (Batch B depth).
 *
 * <p>Every endpoint enforces subject-or-admin ownership against the path
 * {@code customerId}: carts and invoices are private per-customer data, and
 * item pricing is resolved server-side from the restaurant service — the
 * client-supplied {@code unitPrice} is ignored (price-tampering guard).</p>
 */
@RestController
@RequestMapping("/api/v1/customers/{customerId}")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;
    private final OrderInvoiceService invoiceService;
    private final OrderService orderService;
    private final RestaurantPricedItemResolver pricedItemResolver;

    public record AddItemRequest(Long menuItemId, int quantity) {}

    @PostMapping("/cart/items")
    public Cart addItem(@AuthenticationPrincipal TokenPrincipal principal,
                        @PathVariable Long customerId,
                        @RequestBody AddItemRequest request) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        if (request == null || request.menuItemId() == null) {
            throw new BusinessException("menuItemId is required");
        }
        if (request.quantity() <= 0) {
            throw new BusinessException("quantity must be positive");
        }
        // Server-side price resolution: ignore any client-provided name/price.
        RestaurantPricedItemResolver.PricedItem item =
                pricedItemResolver.resolve(request.menuItemId());
        return cartService.addItem(customerId, request.menuItemId(),
                item.name(), item.price(), request.quantity());
    }

    @GetMapping("/cart")
    public List<CartItem> getCart(@AuthenticationPrincipal TokenPrincipal principal,
                                  @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return cartService.getItems(customerId);
    }

    @GetMapping("/cart/subtotal")
    public java.math.BigDecimal subtotal(@AuthenticationPrincipal TokenPrincipal principal,
                                         @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        return cartService.subtotal(customerId);
    }

    @DeleteMapping("/cart")
    public void clearCart(@AuthenticationPrincipal TokenPrincipal principal,
                          @PathVariable Long customerId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        cartService.clear(customerId);
    }

    @GetMapping("/orders/{orderId}/invoice")
    public OrderInvoice invoice(@AuthenticationPrincipal TokenPrincipal principal,
                                @PathVariable Long customerId,
                                @PathVariable Long orderId) {
        PrincipalGuard.requireSelfOrAdmin(principal, customerId);
        // The invoice must belong to the addressed customer, not just exist.
        OrderInvoice invoice = invoiceService.getByOrder(orderId);
        if (!customerId.equals(orderService.getOrder(orderId).customerId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Cannot view another customer's invoice");
        }
        return invoice;
    }
}
