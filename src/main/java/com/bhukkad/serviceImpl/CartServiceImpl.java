package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.CartItemResponse;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.entity.*;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.entity.*;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.*;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CartService;
import com.bhukkad.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CustomerRepository customerRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final SecurityUtils securityUtils;
    private final CouponService couponService;

    @Override
    public CartResponse getCart() {
        Cart cart = getOrCreateCart();
        return mapToCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse addItem(CartItemRequest request) {
        Cart cart = getOrCreateCart();

        MenuItem menuItem = menuItemRepository.findById(request.getMenuItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        if (!menuItem.getAvailable()) {
            throw new BusinessException("Menu item is not available");
        }

        Restaurant restaurant = menuItem.getCategory().getRestaurant();

        // Check if cart has items from different restaurant
        if (cart.getRestaurant() != null && !cart.getRestaurant().getId().equals(restaurant.getId())) {
            throw new BusinessException("Cart contains items from different restaurant. Please clear cart first.");
        }

        cart.setRestaurant(restaurant);

        // Check if item already exists in cart
        CartItem existingItem = cart.getCartItems().stream()
                .filter(item -> item.getMenuItem().getId().equals(menuItem.getId()))
                .findFirst()
                .orElse(null);

        if (existingItem != null) {
            existingItem.setQuantity(existingItem.getQuantity() + request.getQuantity());
        } else {
            CartItem cartItem = new CartItem();
            cartItem.setCart(cart);
            cartItem.setMenuItem(menuItem);
            cartItem.setQuantity(request.getQuantity());
            cartItem.setSpecialInstructions(request.getSpecialInstructions());

            // Handle customizations
            if (request.getCustomizationChoiceIds() != null) {
                // Add customization logic
            }

            cart.getCartItems().add(cartItem);
        }

        cart = cartRepository.save(cart);
        return mapToCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItemQuantity(Long cartItemId, Integer quantity) {
        Cart cart = getOrCreateCart();

        CartItem cartItem = cart.getCartItems().stream()
                .filter(item -> item.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));

        if (quantity <= 0) {
            cart.getCartItems().remove(cartItem);
        } else {
            cartItem.setQuantity(quantity);
        }

        cart = cartRepository.save(cart);
        return mapToCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long cartItemId) {
        Cart cart = getOrCreateCart();

        cart.getCartItems().removeIf(item -> item.getId().equals(cartItemId));

        if (cart.getCartItems().isEmpty()) {
            cart.setRestaurant(null);
        }

        cart = cartRepository.save(cart);
        return mapToCartResponse(cart);
    }

    @Override
    @Transactional
    public void clearCart() {
        Cart cart = getOrCreateCart();
        cart.getCartItems().clear();
        cart.setRestaurant(null);
        cartRepository.save(cart);
    }

    @Override
    @Transactional
    public CartResponse applyCoupon(String couponCode) {
        Cart cart = getOrCreateCart();

        if (cart.getCartItems().isEmpty()) {
            throw new BusinessException("Cart is empty");
        }

        double subtotal = calculateSubtotal(cart);
        Coupon coupon = couponService.validateCoupon(couponCode, subtotal,
                cart.getRestaurant() != null ? cart.getRestaurant().getId() : null);

        // Store coupon in session or return with response
        return mapToCartResponse(cart);
    }

    private Cart getOrCreateCart() {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        return cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setCustomer(customer);
                    newCart.setCartItems(new ArrayList<>());
                    return cartRepository.save(newCart);
                });
    }

    private double calculateSubtotal(Cart cart) {
        return cart.getCartItems().stream()
                .mapToDouble(item -> item.getMenuItem().getPrice() * item.getQuantity())
                .sum();
    }

    private CartResponse mapToCartResponse(Cart cart) {
        double subtotal = calculateSubtotal(cart);
        int itemCount = cart.getCartItems().stream()
                .mapToInt(CartItem::getQuantity)
                .sum();

        return CartResponse.builder()
                .id(cart.getId())
                .restaurantId(cart.getRestaurant() != null ? cart.getRestaurant().getId() : null)
                .restaurantName(cart.getRestaurant() != null ? cart.getRestaurant().getName() : null)
                .items(cart.getCartItems().stream()
                        .map(this::mapToCartItemResponse)
                        .collect(Collectors.toList()))
                .subtotal(subtotal)
                .itemCount(itemCount)
                .build();
    }

    private CartItemResponse mapToCartItemResponse(CartItem cartItem) {
        double totalPrice = cartItem.getMenuItem().getPrice() * cartItem.getQuantity();

        return CartItemResponse.builder()
                .id(cartItem.getId())
                .menuItemId(cartItem.getMenuItem().getId())
                .menuItemName(cartItem.getMenuItem().getName())
                .price(cartItem.getMenuItem().getPrice())
                .quantity(cartItem.getQuantity())
                .customizations(cartItem.getCustomizations().stream()
                        .map(c -> c.getCustomizationChoice().getName())
                        .collect(Collectors.toList()))
                .totalPrice(totalPrice)
                .specialInstructions(cartItem.getSpecialInstructions())
                .build();
    }
}