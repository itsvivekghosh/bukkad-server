package com.bhukkad.controller;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
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
    public ResponseEntity<ApiResponse<CartResponse>> addItem(@Valid @RequestBody CartItemRequest request) {
        CartResponse cart = cartService.addItem(request);
        return ResponseEntity.ok(ApiResponse.success("Item added to cart", cart));
    }

    @PutMapping("/items/{cartItemId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItemQuantity(
            @PathVariable Long cartItemId,
            @RequestParam Integer quantity) {
        CartResponse cart = cartService.updateItemQuantity(cartItemId, quantity);
        return ResponseEntity.ok(ApiResponse.success("Cart updated", cart));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(@PathVariable Long cartItemId) {
        CartResponse cart = cartService.removeItem(cartItemId);
        return ResponseEntity.ok(ApiResponse.success("Item removed from cart", cart));
    }

    @DeleteMapping("/clear")
    public ResponseEntity<ApiResponse<Void>> clearCart() {
        cartService.clearCart();
        return ResponseEntity.ok(ApiResponse.success("Cart cleared", null));
    }

    @PostMapping("/apply-coupon")
    public ResponseEntity<ApiResponse<CartResponse>> applyCoupon(@RequestParam String couponCode) {
        CartResponse cart = cartService.applyCoupon(couponCode);
        return ResponseEntity.ok(ApiResponse.success("Coupon applied", cart));
    }
}