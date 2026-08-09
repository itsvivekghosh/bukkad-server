package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.CartItemResponse;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.entity.*;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.repository.*;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CartService;
import com.bhukkad.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private static final Logger log = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CustomerRepository customerRepository;
    private final MenuItemRepository menuItemRepository;
    private final SecurityUtils securityUtils;
    private final CouponService couponService;

    @Override
    public CartResponse getCart() {
        Long customerId = securityUtils.getCurrentUserId();

        // Use the query with LEFT JOIN FETCH restaurant
        Cart cart = cartRepository.findByCustomerIdWithRestaurant(customerId)
                .orElseGet(() -> createNewCart(customerId));

        return buildCartResponse(cart);
    }

    @Transactional
    private Cart createNewCart(Long customerId) {
        Customer customer = customerRepository.findById(customerId).get();
        Cart newCart = new Cart();
        newCart.setCustomer(customer);
        return cartRepository.save(newCart);
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

        // Check if cart has items from a different restaurant
        if (cart.getRestaurant() != null && !cart.getRestaurant().getId().equals(restaurant.getId())) {
            throw new BusinessException("Cart contains items from different restaurant. Clear cart first.");
        }

        cart.setRestaurant(restaurant);

        // Check if item already exists in cart
        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
        CartItem existingItem = cartItems.stream()
                .filter(item -> item.getMenuItem().getId().equals(menuItem.getId()))
                .findFirst()
                .orElse(null);

        if (existingItem != null) {
            existingItem.setQuantity(existingItem.getQuantity() + request.getQuantity());
            cartItemRepository.save(existingItem);
        } else {
            CartItem cartItem = new CartItem();
            cartItem.setCart(cart);
            cartItem.setMenuItem(menuItem);
            cartItem.setQuantity(request.getQuantity());
            cartItem.setSpecialInstructions(request.getSpecialInstructions());
            cartItemRepository.save(cartItem);
        }

        cart = cartRepository.save(cart);
        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse updateItemQuantity(Long cartItemId, Integer quantity) {
        Cart cart = getOrCreateCart();

        CartItem cartItem = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));

        // Verify cart item belongs to this cart
        if (!cartItem.getCart().getId().equals(cart.getId())) {
            throw new BusinessException("Cart item does not belong to your cart");
        }

        if (quantity <= 0) {
            cartItemRepository.delete(cartItem);

            // Check if cart is empty now
            List<CartItem> remaining = cartItemRepository.findByCartId(cart.getId());
            if (remaining.isEmpty()) {
                cart.setRestaurant(null);
                cartRepository.save(cart);
            }
        } else {
            cartItem.setQuantity(quantity);
            cartItemRepository.save(cartItem);
        }

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse removeItem(Long cartItemId) {
        Cart cart = getOrCreateCart();

        CartItem cartItem = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart item not found"));

        if (!cartItem.getCart().getId().equals(cart.getId())) {
            throw new BusinessException("Cart item does not belong to your cart");
        }

        cartItemRepository.delete(cartItem);

        // Check if cart is empty
        List<CartItem> remaining = cartItemRepository.findByCartId(cart.getId());
        if (remaining.isEmpty()) {
            cart.setRestaurant(null);
            cartRepository.save(cart);
        }

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public void clearCart() {
        Cart cart = getOrCreateCart();
        cartItemRepository.deleteByCartId(cart.getId());
        cart.setRestaurant(null);
        cartRepository.save(cart);
    }

    @Override
    @Transactional(readOnly = true)
    public CartResponse applyCoupon(String couponCode) {
        Cart cart = getOrCreateCart();
        List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());

        if (cartItems.isEmpty()) {
            throw new BusinessException("Cart is empty");
        }

        double subtotal = calculateSubtotal(cartItems);
        Long restaurantId = cart.getRestaurant() != null ? cart.getRestaurant().getId() : null;

        couponService.validateCoupon(couponCode, subtotal, restaurantId);

        return buildCartResponse(cart);
    }

    // ==================== HELPERS ====================

    @Transactional
    private Cart getOrCreateCart() {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        return cartRepository.findByCustomerId(customerId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setCustomer(customer);
                    return cartRepository.save(newCart);
                });
    }

    @Transactional(readOnly = false)
    private CartResponse buildCartResponse(Cart cart) {
        // Fetch cart items explicitly using repository (avoids lazy loading)
        List<CartItem> cartItems = cartItemRepository.findByCartIdWithMenuItem(cart.getId());

        double subtotal = calculateSubtotal(cartItems);
        int itemCount = cartItems.stream().mapToInt(CartItem::getQuantity).sum();

        String restaurantName = null;
        Long restaurantId = null;
        if (cart.getRestaurant() != null) {
            restaurantId = cart.getRestaurant().getId();
            restaurantName = cart.getRestaurant().getName();
        }

        return CartResponse.builder()
                .id(cart.getId())
                .restaurantId(restaurantId)
                .restaurantName(restaurantName)
                .items(cartItems.stream()
                        .map(this::mapToCartItemResponse)
                        .collect(Collectors.toList()))
                .subtotal(subtotal)
                .itemCount(itemCount)
                .build();
    }

    private double calculateSubtotal(List<CartItem> cartItems) {
        return cartItems.stream()
                .mapToDouble(item -> item.getMenuItem().getPrice() * item.getQuantity())
                .sum();
    }

    private CartItemResponse mapToCartItemResponse(CartItem cartItem) {
        MenuItem menuItem = cartItem.getMenuItem();
        double totalPrice = menuItem.getPrice() * cartItem.getQuantity();

        return CartItemResponse.builder()
                .id(cartItem.getId())
                .menuItemId(menuItem.getId())
                .menuItemName(menuItem.getName())
                .price(menuItem.getPrice())
                .quantity(cartItem.getQuantity())
                .customizations(new ArrayList<>())
                .totalPrice(totalPrice)
                .specialInstructions(cartItem.getSpecialInstructions())
                .build();
    }
}