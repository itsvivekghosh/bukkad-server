package com.bhukkad.order.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.order.domain.entity.Cart;
import com.bhukkad.order.domain.entity.CartItem;
import com.bhukkad.order.domain.service.impl.CartService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.bhukkad.order.infrastructure.client.RestaurantClient;

/**
 * Monolith-parity cart surface ({@code /api/v1/cart/**}) served from the
 * caller's validated JWT subject. The canonical, path-scoped surface lives in
 * {@link CartController}; these aliases keep pre-extraction mobile clients
 * working while they migrate (strangler pattern; deprecate with the clients).
 */
@RestController
@RequestMapping("/api/v1/cart")
public class LegacyCartCompatController {

    private final CartService cartService;
    private final RestaurantClient restaurantClient;

    public LegacyCartCompatController(CartService cartService,
                                      RestaurantClient restaurantClient) {
        this.cartService = cartService;
        this.restaurantClient = restaurantClient;
    }

    public record AddItemRequest(Long menuItemId, String name, BigDecimal unitPrice, int quantity) {}

    @PostMapping("/add")
    public Cart add(@AuthenticationPrincipal TokenPrincipal principal,
                    @RequestBody AddItemRequest request) {
        Long customerId = subjectId(principal);
        if (request == null || request.menuItemId() == null || request.menuItemId() <= 0) {
            throw new com.bhukkad.common.error.BusinessException("menuItemId is required");
        }
        // Authoritative name+price from the restaurant service; never trust the
        // caller-supplied snapshot and never let NOT NULL columns explode 500.
        // Error contract (RestaurantClient.getMenuItem): a genuine 404 resolves
        // to an EMPTY mono ("item does not exist"); every other failure — mesh
        // outage, timeout, 5xx — propagates as 503 so a restarting upstream is
        // never misreported as "item missing".
        java.util.Map<String, Object> item;
        try {
            item = restaurantClient.getMenuItem(request.menuItemId())
                    .block(java.time.Duration.ofSeconds(5));
        } catch (RuntimeException meshFailure) {
            throw new com.bhukkad.common.error.UpstreamUnavailableException(
                    "restaurant", meshFailure);
        }
        if (item == null || item.isEmpty()) {
            throw new com.bhukkad.common.error.ResourceNotFoundException(
                    "Menu item not found: " + request.menuItemId());
        }
        String name = String.valueOf(item.getOrDefault("name", "item-" + request.menuItemId()));
        BigDecimal price = new BigDecimal(String.valueOf(item.getOrDefault("price", "0")));
        return cartService.addItem(customerId, request.menuItemId(), name, price, request.quantity());
    }

    @GetMapping
    public Map<String, Object> view(@AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        List<CartItem> items = cartService.getItems(customerId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("subtotal", cartService.subtotal(customerId));
        return body;
    }

    @PostMapping("/clear")
    public Map<String, String> clear(@AuthenticationPrincipal TokenPrincipal principal) {
        cartService.clear(subjectId(principal));
        return Map.of("message", "cart cleared");
    }

    /**
     * Monolith parity: the mobile app DELETEs {@code /cart/clear}. Semantics
     * identical to the POST alias.
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/clear")
    public Map<String, String> clearDelete(@AuthenticationPrincipal TokenPrincipal principal) {
        return clear(principal);
    }

    /**
     * Updates a cart item's quantity (0 removes the line). The item must
     * belong to the caller's active cart — the cart id is resolved
     * server-side, never trusted from the request.
     */
    @org.springframework.web.bind.annotation.PutMapping("/items/{cartItemId}")
    public Map<String, Object> updateItem(@AuthenticationPrincipal TokenPrincipal principal,
                                          @org.springframework.web.bind.annotation.PathVariable Long cartItemId,
                                          @org.springframework.web.bind.annotation.RequestParam int quantity) {
        Long customerId = subjectId(principal);
        if (quantity < 0) {
            throw new com.bhukkad.common.error.BusinessException("Quantity must be zero or positive");
        }
        List<CartItem> items = cartService.getItems(customerId);
        CartItem item = items.stream()
                .filter(i -> i.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new com.bhukkad.common.error.ResourceNotFoundException(
                        "Cart item not found: " + cartItemId));
        if (quantity == 0) {
            cartService.removeItem(customerId, cartItemId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", "item removed");
            body.put("items", cartService.getItems(customerId));
            return body;
        }
        cartService.updateQuantity(customerId, cartItemId, quantity);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "quantity updated");
        body.put("items", cartService.getItems(customerId));
        return body;
    }

    /** Removes a single cart line (monolith parity for the item "x" button). */
    @org.springframework.web.bind.annotation.DeleteMapping("/items/{cartItemId}")
    public Map<String, Object> removeItem(@AuthenticationPrincipal TokenPrincipal principal,
                                          @org.springframework.web.bind.annotation.PathVariable Long cartItemId) {
        Long customerId = subjectId(principal);
        cartService.removeItem(customerId, cartItemId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "item removed");
        body.put("items", cartService.getItems(customerId));
        return body;
    }

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }
}
