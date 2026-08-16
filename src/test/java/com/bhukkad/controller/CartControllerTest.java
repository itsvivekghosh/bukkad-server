package com.bhukkad.controller;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.service.CartService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CartControllerTest {

    @Mock
    private CartService cartService;

    @InjectMocks
    private CartController cartController;

    @Test
    void getCart_returnsCart() {
        CartResponse cart = new CartResponse();
        when(cartService.getCart()).thenReturn(cart);

        ResponseEntity<ApiResponse<CartResponse>> response = cartController.getCart();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(cart, response.getBody().getData());
        verify(cartService).getCart();
    }

    @Test
    void addItem_returnsUpdatedCart() {
        CartItemRequest request = new CartItemRequest();
        CartResponse cart = new CartResponse();
        when(cartService.addItem(request)).thenReturn(cart);

        ResponseEntity<ApiResponse<CartResponse>> response = cartController.addItem(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Item added to cart", response.getBody().getMessage());
        assertEquals(cart, response.getBody().getData());
    }

    @Test
    void updateItemQuantity_returnsUpdatedCart() {
        CartResponse cart = new CartResponse();
        when(cartService.updateItemQuantity(4L, 3)).thenReturn(cart);

        ResponseEntity<ApiResponse<CartResponse>> response = cartController.updateItemQuantity(4L, 3);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Cart updated", response.getBody().getMessage());
        assertEquals(cart, response.getBody().getData());
    }

    @Test
    void removeItem_returnsUpdatedCart() {
        CartResponse cart = new CartResponse();
        when(cartService.removeItem(4L)).thenReturn(cart);

        ResponseEntity<ApiResponse<CartResponse>> response = cartController.removeItem(4L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Item removed from cart", response.getBody().getMessage());
        assertEquals(cart, response.getBody().getData());
    }

    @Test
    void clearCart_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = cartController.clearCart();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Cart cleared", response.getBody().getMessage());
        verify(cartService).clearCart();
    }

    @Test
    void applyCoupon_returnsUpdatedCart() {
        CartResponse cart = new CartResponse();
        when(cartService.applyCoupon("SAVE10")).thenReturn(cart);

        ResponseEntity<ApiResponse<CartResponse>> response = cartController.applyCoupon("SAVE10");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Coupon applied", response.getBody().getMessage());
        assertEquals(cart, response.getBody().getData());
    }
}
