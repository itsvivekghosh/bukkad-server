package com.bhukkad.service;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.CartResponse;

public interface CartService {
    CartResponse getCart();
    CartResponse addItem(CartItemRequest request);
    CartResponse updateItemQuantity(Long cartItemId, Integer quantity);
    CartResponse removeItem(Long cartItemId);
    void clearCart();
    CartResponse applyCoupon(String couponCode);
}