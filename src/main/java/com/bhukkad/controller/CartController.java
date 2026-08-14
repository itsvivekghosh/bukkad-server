package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/cart")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart() {
        CartResponse cart = cartService.getCart();
        return ResponseEntity.ok(ApiResponse.success(cart));
    }

    @PostMapping("/add")
    @RateLimited("cart-mutation")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(@Valid @RequestBody CartItemRequest request) {
        CartResponse cart = cartService.addItem(request);
        return ResponseEntity.ok(ApiResponse.success("Item added to cart", cart));
    }

    @PutMapping("/items/{cartItemId}")
    @RateLimited("cart-mutation")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            @PathVariable Long cartItemId,
            @RequestParam Integer quantity) {
        CartResponse cart = cartService.updateItemQuantity(cartItemId, quantity);
        return ResponseEntity.ok(ApiResponse.success("Cart updated", cart));
    }

    @DeleteMapping("/items/{cartItemId}")
    @RateLimited("cart-mutation")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(@PathVariable Long cartItemId) {
        CartResponse cart = cartService.removeItem(cartItemId);
        return ResponseEntity.ok(ApiResponse.success("Item removed from cart", cart));
    }

    @DeleteMapping("/restaurant/{restaurantId}")
    @RateLimited("cart-mutation")
    public ResponseEntity<ApiResponse<CartResponse>> clearRestaurantCart(@PathVariable Long restaurantId) {
        CartResponse cart = cartService.clearRestaurantCart(restaurantId);
        return ResponseEntity.ok(ApiResponse.success("Restaurant items removed from cart", cart));
    }

    @DeleteMapping("/clear")
    @RateLimited("cart-mutation")
    public ResponseEntity<ApiResponse<Void>> clearCart() {
        cartService.clearCart();
        return ResponseEntity.ok(ApiResponse.success("Cart cleared", null));
    }

    @PostMapping("/apply-coupon")
    @RateLimited("cart-mutation")
    public ResponseEntity<ApiResponse<CartResponse>> applyCoupon(@RequestParam String couponCode) {
        CartResponse cart = cartService.applyCoupon(couponCode);
        return ResponseEntity.ok(ApiResponse.success("Coupon applied", cart));
    }
}