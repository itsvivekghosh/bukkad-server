package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.CartItemResponse;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.dto.response.ReorderResponse;
import com.bhukkad.dto.response.RestaurantCartGroup;
import com.bhukkad.dto.response.SkippedReorderItem;
import com.bhukkad.entity.*;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.*;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CartService;
import com.bhukkad.service.CouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartServiceImpl implements CartService {

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final CustomerRepository customerRepository;
    private final MenuItemRepository menuItemRepository;
    private final OrderRepository orderRepository;
    private final SecurityUtils securityUtils;
    private final CouponService couponService;

    @Override
    @Transactional
    public CartResponse getCart() {
        Long customerId = securityUtils.getCurrentUserId();

        // Use the query with LEFT JOIN FETCH restaurant
        Cart cart = cartRepository.findByCustomerIdWithRestaurant(customerId)
                .orElseGet(() -> createNewCart(customerId));

        return buildCartResponse(cart);
    }

    private Cart createNewCart(Long customerId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        Cart newCart = new Cart();
        newCart.setCustomer(customer);
        return cartRepository.save(newCart);
    }

    @Override
    @Transactional
    public CartResponse addItem(CartItemRequest request) {
        Cart cart = getOrCreateCart();

        MenuItem menuItem = menuItemRepository.findByIdWithDetails(request.getMenuItemId())
                .orElseThrow(() -> new ResourceNotFoundException("Menu item not found"));

        if (!menuItem.getAvailable()) {
            throw new BusinessException("Menu item is not available");
        }

        Restaurant restaurant = menuItem.getCategory().getRestaurant();

        if (cart.getRestaurant() == null) {
            cart.setRestaurant(restaurant);
        }

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

        CartItem cartItem = cartItemRepository.findByIdWithCart(cartItemId)
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
            } else {
                syncCartRestaurant(cart, remaining);
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

        CartItem cartItem = cartItemRepository.findByIdWithCart(cartItemId)
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
        } else {
            syncCartRestaurant(cart, remaining);
        }

        return buildCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse clearRestaurantCart(Long restaurantId) {
        Cart cart = getOrCreateCart();
        List<CartItem> items = cartItemRepository.findByCartIdWithMenuItem(cart.getId());
        for (CartItem item : items) {
            if (item.getMenuItem().getCategory().getRestaurant().getId().equals(restaurantId)) {
                cartItemRepository.delete(item);
            }
        }
        List<CartItem> remaining = cartItemRepository.findByCartId(cart.getId());
        if (remaining.isEmpty()) {
            cart.setRestaurant(null);
        } else {
            syncCartRestaurant(cart, remaining);
        }
        cartRepository.save(cart);
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
    @Transactional
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

    @Override
    @Transactional
    public ReorderResponse reorderFromOrder(Long orderId) {
        Long customerId = securityUtils.getCurrentUserId();
        Order order = orderRepository.findByIdWithDetails(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (!order.getCustomer().getId().equals(customerId)) {
            throw new UnauthorizedException("You can only reorder your own orders");
        }
        if (order.getStatus() == Order.OrderStatus.CANCELLED) {
            throw new BusinessException("Cannot reorder a cancelled order");
        }
        if (order.getOrderItems() == null || order.getOrderItems().isEmpty()) {
            throw new BusinessException("Order has no items to reorder");
        }

        Cart cart = getOrCreateCart();
        Restaurant orderRestaurant = order.getRestaurant();

        if (cart.getRestaurant() != null && !cart.getRestaurant().getId().equals(orderRestaurant.getId())) {
            // Multi-restaurant cart: keep items from other restaurants
        }
        if (cart.getRestaurant() == null) {
            cart.setRestaurant(orderRestaurant);
        }

        List<SkippedReorderItem> skipped = new ArrayList<>();
        int addedCount = 0;

        for (OrderItem orderItem : order.getOrderItems()) {
            MenuItem menuItem = orderItem.getMenuItem();
            if (menuItem == null) {
                skipped.add(SkippedReorderItem.builder()
                        .reason("Menu item no longer exists")
                        .build());
                continue;
            }
            if (!Boolean.TRUE.equals(menuItem.getAvailable())) {
                skipped.add(SkippedReorderItem.builder()
                        .menuItemId(menuItem.getId())
                        .menuItemName(menuItem.getName())
                        .reason("Item is currently unavailable")
                        .build());
                continue;
            }
            if (!menuItem.getCategory().getRestaurant().getId().equals(orderRestaurant.getId())) {
                skipped.add(SkippedReorderItem.builder()
                        .menuItemId(menuItem.getId())
                        .menuItemName(menuItem.getName())
                        .reason("Item no longer sold by this restaurant")
                        .build());
                continue;
            }

            List<CartItem> cartItems = cartItemRepository.findByCartId(cart.getId());
            CartItem existingItem = cartItems.stream()
                    .filter(item -> item.getMenuItem().getId().equals(menuItem.getId()))
                    .findFirst()
                    .orElse(null);

            if (existingItem != null) {
                existingItem.setQuantity(existingItem.getQuantity() + orderItem.getQuantity());
                if (orderItem.getSpecialInstructions() != null) {
                    existingItem.setSpecialInstructions(orderItem.getSpecialInstructions());
                }
                cartItemRepository.save(existingItem);
            } else {
                CartItem cartItem = new CartItem();
                cartItem.setCart(cart);
                cartItem.setMenuItem(menuItem);
                cartItem.setQuantity(orderItem.getQuantity());
                cartItem.setSpecialInstructions(orderItem.getSpecialInstructions());
                cartItemRepository.save(cartItem);
            }
            addedCount++;
        }

        if (addedCount == 0) {
            throw new BusinessException("No items from this order are available to reorder");
        }

        cartRepository.save(cart);
        return ReorderResponse.builder()
                .cart(buildCartResponse(cart))
                .skippedItems(skipped)
                .build();
    }

    // ==================== HELPERS ====================

    private Cart getOrCreateCart() {
        Long customerId = securityUtils.getCurrentUserId();
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));

        return cartRepository.findByCustomerIdWithRestaurant(customerId)
                .orElseGet(() -> {
                    Cart newCart = new Cart();
                    newCart.setCustomer(customer);
                    return cartRepository.save(newCart);
                });
    }

    private CartResponse buildCartResponse(Cart cart) {
        List<CartItem> cartItems = cartItemRepository.findByCartIdWithMenuItem(cart.getId());

        Map<Long, List<CartItem>> byRestaurant = new LinkedHashMap<>();
        for (CartItem item : cartItems) {
            Long restaurantId = item.getMenuItem().getCategory().getRestaurant().getId();
            byRestaurant.computeIfAbsent(restaurantId, id -> new ArrayList<>()).add(item);
        }

        List<RestaurantCartGroup> groups = new ArrayList<>();
        for (Map.Entry<Long, List<CartItem>> entry : byRestaurant.entrySet()) {
            List<CartItem> groupItems = entry.getValue();
            Restaurant restaurant = groupItems.get(0).getMenuItem().getCategory().getRestaurant();
            double groupSubtotal = calculateSubtotal(groupItems);
            int groupItemCount = groupItems.stream().mapToInt(CartItem::getQuantity).sum();
            groups.add(RestaurantCartGroup.builder()
                    .restaurantId(restaurant.getId())
                    .restaurantName(restaurant.getName())
                    .items(groupItems.stream().map(this::mapToCartItemResponse).collect(Collectors.toList()))
                    .subtotal(groupSubtotal)
                    .itemCount(groupItemCount)
                    .build());
        }

        double subtotal = calculateSubtotal(cartItems);
        int itemCount = cartItems.stream().mapToInt(CartItem::getQuantity).sum();

        Long restaurantId = null;
        String restaurantName = null;
        List<CartItemResponse> flatItems = List.of();
        if (groups.size() == 1) {
            RestaurantCartGroup single = groups.get(0);
            restaurantId = single.getRestaurantId();
            restaurantName = single.getRestaurantName();
            flatItems = single.getItems();
        }

        return CartResponse.builder()
                .id(cart.getId())
                .restaurantId(restaurantId)
                .restaurantName(restaurantName)
                .items(flatItems)
                .restaurantCarts(groups)
                .subtotal(subtotal)
                .itemCount(itemCount)
                .build();
    }

    private void syncCartRestaurant(Cart cart, List<CartItem> remaining) {
        if (remaining.isEmpty()) {
            cart.setRestaurant(null);
        } else {
            cart.setRestaurant(remaining.get(0).getMenuItem().getCategory().getRestaurant());
        }
        cartRepository.save(cart);
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