package com.bhukkad.service;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.ReorderResponse;
import com.bhukkad.dto.response.CartResponse;

public interface CartService {
    CartResponse getCart();
    CartResponse addItem(CartItemRequest request);
    CartResponse updateItemQuantity(Long cartItemId, Integer quantity);
    CartResponse removeItem(Long cartItemId);
    void clearCart();
    CartResponse clearRestaurantCart(Long restaurantId);
    CartResponse applyCoupon(String couponCode);
    CartResponse removeCoupon();
    ReorderResponse reorderFromOrder(Long orderId);

    /** Re-adds items from a cancelled/refunded order into the caller's cart. */
    ReorderResponse rebookOrder(Long orderId, Long customerId);
}