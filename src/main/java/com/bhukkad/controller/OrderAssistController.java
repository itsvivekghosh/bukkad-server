package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MenuItemResponse;
import com.bhukkad.dto.response.ReorderResponse;
import com.bhukkad.recommendation.SurpriseMeService;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Customer-facing order-assist endpoints: one-tap rebooking of cancelled or
 * refunded orders and the "surprise me" curated pick.
 *
 * <p>Mounted under {@code /api/v1/customers/**} so the security chain already
 * requires the CUSTOMER role.</p>
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/customers")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Order Assist", description = "Rebook cancelled orders and curated picks for customers")
public class OrderAssistController {

    private final CartService cartService;
    private final SurpriseMeService surpriseMeService;
    private final SecurityUtils securityUtils;

    /**
     * Rebooks a cancelled or refunded order into the customer's cart.
     *
     * <p>Ownership is enforced against the current user's id inside
     * {@link CartServiceImpl#rebookOrder} — an order that does not belong to
     * the authenticated customer is rejected there.</p>
     */
    @PostMapping("/orders/{orderId}/rebook")
    @Operation(summary = "Rebook a cancelled or refunded order into the cart")
    public ResponseEntity<ApiResponse<ReorderResponse>> rebookOrder(@PathVariable Long orderId) {
        ReorderResponse response = cartService.rebookOrder(orderId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Items added to cart", response));
    }

    /** Curated single-item pick for a restaurant ("surprise me"). */
    @GetMapping("/surprise-me")
    @Operation(summary = "Curated surprise item pick for a restaurant")
    public ResponseEntity<ApiResponse<MenuItemResponse>> surpriseMe(
            @RequestParam Long restaurantId) {
        Optional<MenuItemResponse> pick = surpriseMeService.surprisePick(
                securityUtils.getCurrentUserId(), restaurantId);
        return pick.map(item -> ResponseEntity.ok(ApiResponse.success("Surprise pick", item)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("No available items for this restaurant")));
    }
}
