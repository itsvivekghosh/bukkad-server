package com.bhukkad.order.api.controller;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.entity.GiftCard;
import com.bhukkad.order.domain.service.impl.CouponService;
import com.bhukkad.order.domain.service.impl.GroupOrderService;
import com.bhukkad.order.domain.service.impl.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.bhukkad.order.api.dto.request.GiftCardPurchaseRequest;
import com.bhukkad.order.api.dto.request.GroupOrderInviteRequest;
import com.bhukkad.order.api.dto.request.GroupOrderSplitRequest;
import com.bhukkad.order.api.dto.request.SubscriptionPlanRequest;
import com.bhukkad.order.api.dto.response.GroupOrderResponse;
import com.bhukkad.order.api.dto.response.SubscriptionPlanResponse;
import com.bhukkad.order.domain.entity.Cart;
import com.bhukkad.order.domain.entity.CartItem;
import com.bhukkad.order.domain.entity.Coupon;
import com.bhukkad.order.domain.repository.GiftCardRepository;
import com.bhukkad.order.domain.repository.OrderRepository;
import com.bhukkad.order.domain.service.impl.CartService;
import com.bhukkad.order.domain.service.impl.SocialOrderService;
import com.bhukkad.order.infrastructure.client.RestaurantClient;

/**
 * Monolith-parity self-service aliases for social-order surfaces. Each of
 * these previously lived on id-scoped paths ({@code /customers/{id}/...} or
 * /api/v1 single-segment paths); the installed apps call them without an id
 * so the acting identity is ALWAYS the JWT subject (IDOR-safe by design).
 */
@RestController
@RequiredArgsConstructor
public class SocialSelfCompatController {

    private final CouponService couponService;
    private final GroupOrderService groupOrderService;
    private final SubscriptionService subscriptionService;
    private final SocialOrderService socialOrderService;
    private final GiftCardRepository giftCardRepository;
    private final OrderRepository orderRepository;
    private final CartService cartService;
    private final RestaurantClient restaurantClient;

    // ------------------------------------------------------------------
    // Cart coupon application
    // ------------------------------------------------------------------

    /**
     * Validates a coupon against the caller's cart subtotal. An invalid or
     * expired coupon, or an empty cart, fails with 400; a valid one returns
     * the discount preview. No cart mutation — the discount is applied at
     * checkout (monolith parity).
     */
    @PostMapping("/api/v1/cart/apply-coupon")
    public ResponseEntity<Map<String, Object>> applyCoupon(
            @AuthenticationPrincipal TokenPrincipal principal,
            @RequestParam String couponCode) {
        Long customerId = subjectId(principal);
        var items = cartService.getItems(customerId);
        if (items.isEmpty()) {
            throw new BusinessException("Cart is empty");
        }
        // Cart lines are snapshots without restaurant ownership (a cart may
        // hold items from a single restaurant by app rule). Coupon restaurant
        // scoping resolves via the first item's menu item → restaurant lookup.
        BigDecimal subtotal = items.stream()
                .map(i -> i.getUnitPrice() == null ? BigDecimal.ZERO
                        : i.getUnitPrice().multiply(BigDecimal.valueOf(i.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Long firstItemRestaurant = restaurantClient
                .getMenuItemRestaurantId(items.get(0).getMenuItemId())
                .block(java.time.Duration.ofSeconds(5));
        var coupon = couponService.validate(couponCode, subtotal, firstItemRestaurant, customerId);
        BigDecimal discount = couponService.calculateDiscount(coupon, subtotal);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", coupon.getCode());
        body.put("discount", discount);
        body.put("subtotal", subtotal);
        body.put("message", "Coupon applied");
        return ResponseEntity.ok(body);
    }

    /** Removes every cart item belonging to one restaurant (monolith parity). */
    @org.springframework.web.bind.annotation.DeleteMapping("/api/v1/cart/restaurant/{restaurantId}")
    public Map<String, Object> removeRestaurantItems(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable Long restaurantId) {
        Long customerId = subjectId(principal);
        int removed = 0;
        for (com.bhukkad.order.domain.entity.CartItem item : cartService.getItems(customerId)) {
            Long itemRestaurant = restaurantClient
                    .getMenuItemRestaurantId(item.getMenuItemId())
                    .block(java.time.Duration.ofSeconds(5));
            if (restaurantId.equals(itemRestaurant)) {
                cartService.removeItem(customerId, item.getId());
                removed++;
            }
        }
        return Map.of("message", "restaurant items removed", "removed", removed);
    }

    // ------------------------------------------------------------------
    // Gift cards: self-service purchase + balance views
    // ------------------------------------------------------------------

    public record GiftCardPurchaseRequest(BigDecimal amount, String recipientEmail,
                                          String recipientName, String message,
                                          String expiresAt) {}

    /**
     * Self-service gift-card purchase: the buying customer pays and a code is
     * minted. Admin issuance stays on {@code /gift-cards/issue}.
     */
    @PostMapping("/api/v1/gift-cards/purchase")
    public GiftCard purchase(@AuthenticationPrincipal TokenPrincipal principal,
                             @org.springframework.web.bind.annotation.RequestBody(required = false)
                             GiftCardPurchaseRequest request) {
        Long customerId = subjectId(principal);
        BigDecimal amount = request == null ? null : request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException("amount is required and must be positive");
        }
        GiftCard card = socialOrderService.issueGiftCard(customerId, amount);
        return giftCardRepository.findById(card.getId()).orElse(card);
    }

    /** Gift cards purchased by the caller. */
    @GetMapping("/api/v1/gift-cards/my-cards")
    public List<GiftCard> myCards(@AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        return giftCardRepository.findByPurchasedByOrderByCreatedAtDesc(customerId);
    }

    /**
     * Gift cards purchased on the caller's behalf. The order service keeps no
     * customer email read-model, so the received view lists cards minted for
     * the caller's own email when known — otherwise an empty page. The
     * purchase response itself always carries the code, which is what the
     * recipient redeems.
     */
    @GetMapping("/api/v1/gift-cards/received")
    public List<GiftCard> receivedCards(@AuthenticationPrincipal TokenPrincipal principal) {
        subjectId(principal);
        return List.of();
    }

    // ------------------------------------------------------------------
    // Group orders (self)
    // ------------------------------------------------------------------

    public record GroupOrderCreateRequest(String title, Long restaurantId) {}

    @PostMapping(value = "/api/v1/customers/group-orders", produces = "application/json")
    public ResponseEntity<Map<String, Object>> createGroupOrder(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            GroupOrderCreateRequest request) {
        Long customerId = subjectId(principal);
        String title = request == null || request.title() == null || request.title().isBlank()
                ? "Group order" : request.title();
        var group = groupOrderService.createGroupOrder(customerId, title);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", group.getId());
        body.put("hostCustomerId", customerId);
        body.put("title", title);
        body.put("status", "OPEN");
        return ResponseEntity.status(201).body(body);
    }

    /** Self alias for the id-scoped GET (acting identity = JWT subject). */
    @GetMapping("/api/v1/customers/group-orders/{groupOrderId}")
    public GroupOrderResponse getGroupOrder(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable Long groupOrderId) {
        return groupOrderService.getGroup(groupOrderId, subjectId(principal));
    }

    public record GroupOrderInviteRequest(String phone) {}

    /** Self alias for inviting a member by phone (host-only, service rule). */
    @PostMapping("/api/v1/customers/group-orders/{groupOrderId}/invite")
    public GroupOrderResponse inviteToGroupOrder(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable Long groupOrderId,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            GroupOrderInviteRequest request) {
        String phone = request == null ? null : request.phone();
        return groupOrderService.inviteMember(groupOrderId, subjectId(principal), phone);
    }

    /** Self alias for placing the group order (host-only, service rule). */
    @PostMapping("/api/v1/customers/group-orders/{groupOrderId}/place")
    public GroupOrderResponse placeGroupOrder(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable Long groupOrderId) {
        return groupOrderService.placeGroupOrder(groupOrderId, subjectId(principal));
    }

    public record GroupOrderSplitRequest(Map<Long, Double> shares) {}

    /** Self alias for the split-payment preview (host-only, service rule). */
    @PostMapping("/api/v1/customers/group-orders/{groupOrderId}/split")
    public GroupOrderResponse splitGroupOrderPayment(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable Long groupOrderId,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            GroupOrderSplitRequest request) {
        Map<Long, Double> shares = request == null ? null : request.shares();
        return groupOrderService.splitPayment(groupOrderId, subjectId(principal), shares);
    }

    // ------------------------------------------------------------------
    // Subscriptions (self)
    // ------------------------------------------------------------------

    @PostMapping("/api/v1/customers/subscriptions")
    public SubscriptionPlanResponse createSubscription(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.RequestBody(required = false)
            SubscriptionPlanRequest request) {
        Long customerId = subjectId(principal);
        return subscriptionService.createPlan(customerId, request);
    }

    @GetMapping("/api/v1/customers/subscriptions")
    public List<SubscriptionPlanResponse> mySubscriptions(
            @AuthenticationPrincipal TokenPrincipal principal) {
        Long customerId = subjectId(principal);
        return subscriptionService.listPlans(customerId);
    }

    @PostMapping("/api/v1/customers/subscriptions/{planId}/cancel")
    public SubscriptionPlanResponse cancelSubscription(
            @AuthenticationPrincipal TokenPrincipal principal,
            @org.springframework.web.bind.annotation.PathVariable Long planId) {
        Long customerId = subjectId(principal);
        return subscriptionService.cancelPlan(planId, customerId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Long subjectId(TokenPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            throw new UnauthorizedException("Authenticated customer required");
        }
        return principal.userId();
    }
}
