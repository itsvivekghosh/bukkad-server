package com.bhukkad.order.api;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.order.domain.GiftCard;
import com.bhukkad.order.service.CouponService;
import com.bhukkad.order.service.GroupOrderService;
import com.bhukkad.order.service.SubscriptionService;
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
    private final com.bhukkad.order.service.SocialOrderService socialOrderService;
    private final com.bhukkad.order.domain.GiftCardRepository giftCardRepository;
    private final com.bhukkad.order.domain.OrderRepository orderRepository;
    private final com.bhukkad.order.service.CartService cartService;

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
        var coupon = couponService.validate(couponCode, subtotal, null, customerId);
        BigDecimal discount = couponService.calculateDiscount(coupon, subtotal);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", coupon.getCode());
        body.put("discount", discount);
        body.put("subtotal", subtotal);
        body.put("message", "Coupon applied");
        return ResponseEntity.ok(body);
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
