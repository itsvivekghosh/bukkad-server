package com.bhukkad.serviceImpl;

import com.bhukkad.dto.request.CartItemRequest;
import com.bhukkad.dto.response.CartResponse;
import com.bhukkad.entity.Cart;
import com.bhukkad.entity.CartItem;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.metrics.BusinessMetrics;
import com.bhukkad.repository.CartItemRepository;
import com.bhukkad.repository.CartRepository;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.service.CouponService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("deprecation") // legacy single-restaurant CartResponse fields are still asserted for backward compatibility
class CartServiceImplTest {

    @Mock
    private CartRepository cartRepository;
    @Mock
    private CartItemRepository cartItemRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private CouponService couponService;
    @Mock
    private BusinessMetrics businessMetrics;

    @InjectMocks
    private CartServiceImpl cartService;

    @Test
    void getCart_existingCart_mapsItemsAndRestaurant() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Cart cart = cart(5L, restaurant(10L, "Spice Hub"));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(
                cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 2, "extra raita")
        ));

        CartResponse response = cartService.getCart();

        assertEquals(5L, response.getId());
        assertEquals(10L, response.getRestaurantId());
        assertEquals("Spice Hub", response.getRestaurantName());
        assertEquals(400.0, response.getSubtotal());
        assertEquals(2, response.getItemCount());
        assertEquals(1, response.getItems().size());
        assertEquals(20L, response.getItems().get(0).getId());
        assertEquals(30L, response.getItems().get(0).getMenuItemId());
        assertEquals("Biryani", response.getItems().get(0).getMenuItemName());
        assertEquals(200.0, response.getItems().get(0).getPrice());
        assertEquals(2, response.getItems().get(0).getQuantity());
        assertEquals(400.0, response.getItems().get(0).getTotalPrice());
        assertEquals("extra raita", response.getItems().get(0).getSpecialInstructions());
        assertTrue(response.getItems().get(0).getCustomizations().isEmpty());
    }

    @Test
    void getCart_missingCart_createsNewEmptyCart() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.empty());
        Customer customer = customer(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> {
            Cart saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });
        when(cartItemRepository.findByCartIdWithMenuItem(7L)).thenReturn(List.of());

        CartResponse response = cartService.getCart();

        assertEquals(7L, response.getId());
        assertNull(response.getRestaurantId());
        assertNull(response.getRestaurantName());
        assertEquals(0.0, response.getSubtotal());
        assertEquals(0, response.getItemCount());
        assertTrue(response.getItems().isEmpty());
        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository).save(captor.capture());
        assertEquals(customer, captor.getValue().getCustomer());
    }

    @Test
    void getCart_missingCartAndCustomer_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.empty());
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> cartService.getCart());
        assertEquals("Customer not found", ex.getMessage());
    }

    @Test
    void addItem_menuItemNotFound_throws() {
        stubGetOrCreateCart(cart(5L, null));
        CartItemRequest request = cartItemRequest(30L, 1, null);
        when(menuItemRepository.findByIdWithDetails(30L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> cartService.addItem(request));
        assertEquals("Menu item not found", ex.getMessage());
    }

    @Test
    void addItem_unavailable_throws() {
        stubGetOrCreateCart(cart(5L, null));
        MenuItem item = menuItem(30L, "Biryani", 200.0);
        item.setAvailable(false);
        when(menuItemRepository.findByIdWithDetails(30L)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.addItem(cartItemRequest(30L, 1, null)));
        assertEquals("Menu item is not available", ex.getMessage());
    }

    @Test
    void addItem_differentRestaurant_allowsMultiRestaurantCart() {
        Cart cart = cart(5L, restaurant(10L, "Old Place"));
        stubGetOrCreateCart(cart);
        MenuItem item = menuItem(30L, "Pizza", 150.0);
        item.getCategory().setRestaurant(restaurant(11L, "New Place"));
        when(menuItemRepository.findByIdWithDetails(30L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(
                cartItem(21L, cart, item, 1, null)
        ));

        CartResponse response = cartService.addItem(cartItemRequest(30L, 1, null));

        assertEquals(1, response.getRestaurantCarts().size());
        assertEquals(11L, response.getRestaurantCarts().get(0).getRestaurantId());
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void addItem_existingItem_incrementsQuantity() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        MenuItem item = menuItem(30L, "Biryani", 200.0);
        item.getCategory().setRestaurant(restaurant);
        when(menuItemRepository.findByIdWithDetails(30L)).thenReturn(Optional.of(item));
        CartItem existing = cartItem(20L, cart, item, 1, null);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(existing));
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(existing));

        CartResponse response = cartService.addItem(cartItemRequest(30L, 2, "spicy"));

        assertEquals(3, existing.getQuantity());
        verify(cartItemRepository).save(existing);
        assertEquals(10L, response.getRestaurantId());
    }

    @Test
    void addItem_newItem_savesCartItem() {
        Cart cart = cart(5L, null);
        stubGetOrCreateCart(cart);
        MenuItem item = menuItem(30L, "Biryani", 200.0);
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        item.getCategory().setRestaurant(restaurant);
        when(menuItemRepository.findByIdWithDetails(30L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(
                cartItem(21L, cart, item, 2, "no onion")
        ));

        CartResponse response = cartService.addItem(cartItemRequest(30L, 2, "no onion"));

        ArgumentCaptor<CartItem> captor = ArgumentCaptor.forClass(CartItem.class);
        verify(cartItemRepository).save(captor.capture());
        CartItem saved = captor.getValue();
        assertEquals(cart, saved.getCart());
        assertEquals(item, saved.getMenuItem());
        assertEquals(2, saved.getQuantity());
        assertEquals("no onion", saved.getSpecialInstructions());
        assertEquals(restaurant, cart.getRestaurant());
        assertEquals(400.0, response.getSubtotal());
    }

    @Test
    void updateItemQuantity_itemNotFound_throws() {
        stubGetOrCreateCart(cart(5L, restaurant(10L, "Spice Hub")));
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> cartService.updateItemQuantity(20L, 3));
        assertEquals("Cart item not found", ex.getMessage());
    }

    @Test
    void updateItemQuantity_wrongCart_throws() {
        Cart cart = cart(5L, restaurant(10L, "Spice Hub"));
        stubGetOrCreateCart(cart);
        Cart otherCart = cart(99L, restaurant(10L, "Spice Hub"));
        CartItem item = cartItem(20L, otherCart, menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> cartService.updateItemQuantity(20L, 3));
        assertEquals("Cart item does not belong to your cart", ex.getMessage());
    }

    @Test
    void updateItemQuantity_zero_deletesAndClearsRestaurantWhenEmpty() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of());

        CartResponse response = cartService.updateItemQuantity(20L, 0);

        verify(cartItemRepository).delete(item);
        assertNull(cart.getRestaurant());
        verify(cartRepository).save(cart);
        assertEquals(0.0, response.getSubtotal());
    }

    @Test
    void updateItemQuantity_negative_deletesButKeepsRestaurantWhenItemsRemain() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        MenuItem remainingMenu = menuItem(31L, "Naan", 40.0);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        CartItem remaining = cartItem(21L, cart, remainingMenu, 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(remaining));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(remaining));

        cartService.updateItemQuantity(20L, -1);

        verify(cartItemRepository).delete(item);
        assertEquals(restaurant, cart.getRestaurant());
        verify(cartRepository).save(cart);
    }

    @Test
    void updateItemQuantity_positive_updatesQuantity() {
        Cart cart = cart(5L, restaurant(10L, "Spice Hub"));
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(item));

        cartService.updateItemQuantity(20L, 4);

        assertEquals(4, item.getQuantity());
        verify(cartItemRepository).save(item);
        verify(cartItemRepository, never()).delete(any());
    }

    @Test
    void removeItem_notFound_throws() {
        stubGetOrCreateCart(cart(5L, restaurant(10L, "Spice Hub")));
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> cartService.removeItem(20L));
        assertEquals("Cart item not found", ex.getMessage());
    }

    @Test
    void removeItem_wrongCart_throws() {
        Cart cart = cart(5L, restaurant(10L, "Spice Hub"));
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart(99L, restaurant(10L, "Spice Hub")),
                menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));

        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.removeItem(20L));
        assertEquals("Cart item does not belong to your cart", ex.getMessage());
    }

    @Test
    void removeItem_lastItem_clearsRestaurant() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of());

        cartService.removeItem(20L);

        verify(cartItemRepository).delete(item);
        assertNull(cart.getRestaurant());
        verify(cartRepository).save(cart);
    }

    @Test
    void removeItem_remainingItems_keepsRestaurant() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        CartItem remaining = cartItem(21L, cart, menuItem(31L, "Naan", 40.0), 1, null);
        when(cartItemRepository.findByIdWithCart(20L)).thenReturn(Optional.of(item));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(remaining));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(remaining));

        cartService.removeItem(20L);

        assertEquals(restaurant, cart.getRestaurant());
        verify(cartRepository).save(cart);
    }

    @Test
    void clearCart_deletesItemsAndClearsRestaurant() {
        Cart cart = cart(5L, restaurant(10L, "Spice Hub"));
        stubGetOrCreateCart(cart);

        cartService.clearCart();

        verify(cartItemRepository).deleteByCartId(5L);
        assertNull(cart.getRestaurant());
        verify(cartRepository).save(cart);
    }

    @Test
    void applyCoupon_emptyCart_throws() {
        stubGetOrCreateCart(cart(5L, restaurant(10L, "Spice Hub")));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.applyCoupon("SAVE10"));
        assertEquals("Cart is empty", ex.getMessage());
    }

    @Test
    void applyCoupon_withRestaurant_validatesAgainstRestaurant() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 2, null);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(item));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(item));

        CartResponse response = cartService.applyCoupon("SAVE10");

        verify(couponService).validateCoupon("SAVE10", 400.0, 10L);
        assertEquals(400.0, response.getSubtotal());
    }

    @Test
    void applyCoupon_withoutRestaurant_passesNullRestaurantId() {
        Cart cart = cart(5L, null);
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(item));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(item));

        cartService.applyCoupon("SAVE10");

        verify(couponService).validateCoupon("SAVE10", 200.0, null);
    }

    @Test
    void getOrCreateCart_customerNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class, () -> cartService.clearCart());
        assertEquals("Customer not found", ex.getMessage());
    }

    @Test
    void getOrCreateCart_createsCartWhenMissing() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Customer customer = customer(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer));
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> {
            Cart saved = inv.getArgument(0);
            saved.setId(8L);
            return saved;
        });

        cartService.clearCart();

        ArgumentCaptor<Cart> captor = ArgumentCaptor.forClass(Cart.class);
        verify(cartRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertEquals(customer, captor.getAllValues().get(0).getCustomer());
        verify(cartItemRepository).deleteByCartId(8L);
    }

    @Test
    void reorderFromOrder_addsAvailableItemsToCart() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Customer customer = customer(1L);
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        MenuItem menuItem = menuItem(30L, "Biryani", 200.0);
        menuItem.getCategory().setRestaurant(restaurant);

        Order order = new Order();
        order.setId(99L);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setStatus(Order.OrderStatus.DELIVERED);
        OrderItem orderItem = new OrderItem();
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        orderItem.setSpecialInstructions("less spicy");
        order.setOrderItems(List.of(orderItem));

        when(orderRepository.findByIdWithDetails(99L)).thenReturn(Optional.of(order));
        Cart cart = cart(5L, null);
        cart.setCustomer(customer);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        // The optimized reorder fetches cart items once up front via findByCartId
        CartItem existing = cartItem(20L, cart, menuItem, 2, "less spicy");
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(existing));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(existing));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));
        // No customerRepository stub needed: getOrCreateCart only loads the
        // customer when creating a brand-new cart (cart absent here → no DB call).

        var response = cartService.reorderFromOrder(99L);

        assertEquals(1, response.getCart().getItems().size());
        assertTrue(response.getSkippedItems().isEmpty());
        verify(cartItemRepository).save(any(CartItem.class));
    }

    @Test
    void reorderFromOrder_cancelledOrder_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Order order = new Order();
        order.setCustomer(customer(1L));
        order.setStatus(Order.OrderStatus.CANCELLED);
        order.setOrderItems(List.of(new OrderItem()));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> cartService.reorderFromOrder(1L));
    }

    @Test
    void reorderFromOrder_noItems_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Order order = new Order();
        order.setCustomer(customer(1L));
        order.setStatus(Order.OrderStatus.DELIVERED);
        order.setOrderItems(List.of());
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        assertThrows(BusinessException.class, () -> cartService.reorderFromOrder(1L));
    }

    @Test
    void reorderFromOrder_skippedUnavailableItem() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Customer customer = customer(1L);
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        MenuItem menuItem = menuItem(30L, "Biryani", 200.0);
        menuItem.getCategory().setRestaurant(restaurant);
        menuItem.setAvailable(false);

        Order order = new Order();
        order.setId(99L);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setStatus(Order.OrderStatus.DELIVERED);
        OrderItem orderItem = new OrderItem();
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        order.setOrderItems(List.of(orderItem));

        when(orderRepository.findByIdWithDetails(99L)).thenReturn(Optional.of(order));
        Cart cart = cart(5L, null);
        cart.setCustomer(customer);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));

        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.reorderFromOrder(99L));
        assertEquals("No items from this order are available to reorder", ex.getMessage());
    }

    @Test
    void reorderFromOrder_skippedDifferentRestaurantItem() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Customer customer = customer(1L);
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Restaurant otherRestaurant = restaurant(11L, "Other Place");
        MenuItem menuItem = menuItem(30L, "Biryani", 200.0);
        menuItem.getCategory().setRestaurant(otherRestaurant);
        menuItem.setAvailable(true);

        Order order = new Order();
        order.setId(99L);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setStatus(Order.OrderStatus.DELIVERED);
        OrderItem orderItem = new OrderItem();
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        order.setOrderItems(List.of(orderItem));

        when(orderRepository.findByIdWithDetails(99L)).thenReturn(Optional.of(order));
        Cart cart = cart(5L, null);
        cart.setCustomer(customer);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));

        assertThrows(BusinessException.class, () -> cartService.reorderFromOrder(99L));
    }

    @Test
    void reorderFromOrder_withExistingItemIncrementsQuantity() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Customer customer = customer(1L);
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        MenuItem menuItem = menuItem(30L, "Biryani", 200.0);
        menuItem.getCategory().setRestaurant(restaurant);

        Order order = new Order();
        order.setId(99L);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setStatus(Order.OrderStatus.DELIVERED);
        OrderItem orderItem = new OrderItem();
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        orderItem.setSpecialInstructions("extra spicy");
        order.setOrderItems(List.of(orderItem));

        when(orderRepository.findByIdWithDetails(99L)).thenReturn(Optional.of(order));
        Cart cart = cart(5L, restaurant);
        cart.setCustomer(customer);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        CartItem existing = cartItem(20L, cart, menuItem, 1, null);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(existing));
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(existing));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = cartService.reorderFromOrder(99L);

        assertEquals(3, existing.getQuantity());
        assertEquals("extra spicy", existing.getSpecialInstructions());
        verify(cartItemRepository).save(existing);
    }

    @Test
    void reorderFromOrder_withDifferentRestaurantCart_keepsExisting() {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        Customer customer = customer(1L);
        Restaurant orderRestaurant = restaurant(10L, "Spice Hub");
        Restaurant cartRestaurant = restaurant(11L, "Other Place");
        MenuItem menuItem = menuItem(30L, "Biryani", 200.0);
        menuItem.getCategory().setRestaurant(orderRestaurant);

        Order order = new Order();
        order.setId(99L);
        order.setCustomer(customer);
        order.setRestaurant(orderRestaurant);
        order.setStatus(Order.OrderStatus.DELIVERED);
        OrderItem orderItem = new OrderItem();
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        order.setOrderItems(List.of(orderItem));

        when(orderRepository.findByIdWithDetails(99L)).thenReturn(Optional.of(order));
        Cart cart = cart(5L, cartRestaurant);
        cart.setCustomer(customer);
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());
        when(cartItemRepository.findByCartIdWithMenuItem(5L)).thenReturn(List.of(
                cartItem(21L, cart, menuItem, 2, null)
        ));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        var response = cartService.reorderFromOrder(99L);

        assertEquals(cartRestaurant, cart.getRestaurant());
        assertNotNull(response.getCart());
    }

    @Test
    void applyCoupon_couponServiceException_throws() {
        Cart cart = cart(5L, restaurant(10L, "Spice Hub"));
        stubGetOrCreateCart(cart);
        CartItem item = cartItem(20L, cart, menuItem(30L, "Biryani", 200.0), 1, null);
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(item));
        when(couponService.validateCoupon("INVALID", 200.0, 10L)).thenThrow(new RuntimeException("bad coupon"));

        BusinessException ex = assertThrows(BusinessException.class, () -> cartService.applyCoupon("INVALID"));
        assertTrue(ex.getMessage().contains("Invalid coupon"));
    }

    @Test
    void clearRestaurantCart_removesMatchingItems() {
        Restaurant restaurant1 = restaurant(10L, "Spice Hub");
        Restaurant restaurant2 = restaurant(11L, "Other Place");
        Cart cart = cart(5L, restaurant1);
        stubGetOrCreateCart(cart);
        MenuItem item1 = menuItem(30L, "Biryani", 200.0);
        item1.getCategory().setRestaurant(restaurant1);
        MenuItem item2 = menuItem(31L, "Pizza", 300.0);
        item2.getCategory().setRestaurant(restaurant2);
        CartItem cartItem1 = cartItem(20L, cart, item1, 1, null);
        CartItem cartItem2 = cartItem(21L, cart, item2, 1, null);
        when(cartItemRepository.findByCartIdWithMenuItem(5L))
                .thenReturn(List.of(cartItem1, cartItem2))
                .thenReturn(List.of(cartItem2));
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of(cartItem2));
        when(cartRepository.save(cart)).thenReturn(cart);

        CartResponse response = cartService.clearRestaurantCart(10L);

        verify(cartItemRepository).delete(cartItem1);
        assertEquals(restaurant2, cart.getRestaurant());
        assertNotNull(response);
    }

    @Test
    void clearRestaurantCart_removesAllAndClearsRestaurant() {
        Restaurant restaurant = restaurant(10L, "Spice Hub");
        Cart cart = cart(5L, restaurant);
        stubGetOrCreateCart(cart);
        MenuItem item = menuItem(30L, "Biryani", 200.0);
        item.getCategory().setRestaurant(restaurant);
        CartItem cartItem = cartItem(20L, cart, item, 1, null);
        when(cartItemRepository.findByCartIdWithMenuItem(5L))
                .thenReturn(List.of(cartItem))
                .thenReturn(List.of());
        when(cartItemRepository.findByCartId(5L)).thenReturn(List.of());
        when(cartRepository.save(cart)).thenReturn(cart);

        cartService.clearRestaurantCart(10L);

        assertNull(cart.getRestaurant());
    }

    private void stubGetOrCreateCart(Cart cart) {
        when(securityUtils.getCurrentUserId()).thenReturn(1L);
        // No customerRepository stub needed: getOrCreateCart only loads the
        // customer when a new cart must be created (cart absent).
        when(cartRepository.findByCustomerIdWithRestaurant(1L)).thenReturn(Optional.of(cart));
    }

    private CartItemRequest cartItemRequest(Long menuItemId, int quantity, String instructions) {
        CartItemRequest request = new CartItemRequest();
        request.setMenuItemId(menuItemId);
        request.setQuantity(quantity);
        request.setSpecialInstructions(instructions);
        return request;
    }

    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        return customer;
    }

    private Restaurant restaurant(Long id, String name) {
        Restaurant restaurant = new Restaurant();
        restaurant.setId(id);
        restaurant.setName(name);
        return restaurant;
    }

    private Cart cart(Long id, Restaurant restaurant) {
        Cart cart = new Cart();
        cart.setId(id);
        cart.setRestaurant(restaurant);
        return cart;
    }

    private MenuItem menuItem(Long id, String name, double price) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setName(name);
        item.setPrice(price);
        item.setAvailable(true);
        MenuCategory category = new MenuCategory();
        category.setRestaurant(restaurant(10L, "Spice Hub"));
        item.setCategory(category);
        return item;
    }

    private CartItem cartItem(Long id, Cart cart, MenuItem menuItem, int quantity, String instructions) {
        CartItem item = new CartItem();
        item.setId(id);
        item.setCart(cart);
        item.setMenuItem(menuItem);
        item.setQuantity(quantity);
        item.setSpecialInstructions(instructions);
        return item;
    }
}
