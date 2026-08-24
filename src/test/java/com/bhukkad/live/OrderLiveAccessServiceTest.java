package com.bhukkad.live;

import com.bhukkad.entity.Customer;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderLiveAccessServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderLiveAccessService accessService;

    @Test
    void canSubscribeKitchen_returnsTrueForOwner() {
        User owner = ownerUser(5L);
        Restaurant restaurant = restaurantOwnedBy(5L, 10L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertTrue(accessService.canSubscribeKitchen(owner, 10L));
    }

    @Test
    void canSubscribeKitchen_returnsFalseForNonOwner() {
        User owner = ownerUser(5L);
        Restaurant restaurant = restaurantOwnedBy(99L, 10L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertFalse(accessService.canSubscribeKitchen(owner, 10L));
    }

    @Test
    void canSubscribeKitchen_returnsFalseForCustomer() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);

        assertFalse(accessService.canSubscribeKitchen(customer, 10L));
    }

    @Test
    void canSubscribeKitchen_returnsFalseWhenRestaurantMissing() {
        User owner = ownerUser(5L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.empty());

        assertFalse(accessService.canSubscribeKitchen(owner, 10L));
    }

    @Test
    void canSubscribeRider_returnsTrueForMatchingAgent() {
        User agent = userWithRole(User.UserRole.DELIVERY_AGENT, 7L);

        assertTrue(accessService.canSubscribeRider(agent, 7L));
    }

    @Test
    void canSubscribeRider_returnsFalseForDifferentAgent() {
        User agent = userWithRole(User.UserRole.DELIVERY_AGENT, 7L);

        assertFalse(accessService.canSubscribeRider(agent, 8L));
    }

    @Test
    void verifyKitchenAccess_throwsWhenUnauthorized() {
        User owner = ownerUser(5L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> accessService.verifyKitchenAccess(owner, 10L));
    }

    private static User ownerUser(Long id) {
        return userWithRole(User.UserRole.RESTAURANT_OWNER, id);
    }

    private static User userWithRole(User.UserRole role, Long id) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private static Restaurant restaurantOwnedBy(Long ownerId, Long restaurantId) {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(ownerId);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(restaurantId);
        restaurant.setOwner(owner);
        return restaurant;
    }

    // ==================== additional coverage ====================

    @Test
    void canSubscribeKitchen_returnsFalseWhenRestaurantHasNullOwner() {
        User owner = ownerUser(5L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        restaurant.setOwner(null);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertFalse(accessService.canSubscribeKitchen(owner, 10L));
    }

    @Test
    void canSubscribeCustomer_returnsTrueForOwnOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        Order order = orderOwnedBy(1L, 42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertTrue(accessService.canSubscribeCustomer(customer, 42L));
    }

    @Test
    void canSubscribeCustomer_returnsFalseForOthersOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        Order order = orderOwnedBy(2L, 42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertFalse(accessService.canSubscribeCustomer(customer, 42L));
    }

    @Test
    void canSubscribeCustomer_returnsFalseForMissingOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());

        assertFalse(accessService.canSubscribeCustomer(customer, 42L));
    }

    @Test
    void canSubscribeCustomer_returnsFalseForNonCustomer() {
        User agent = userWithRole(User.UserRole.DELIVERY_AGENT, 1L);

        assertFalse(accessService.canSubscribeCustomer(agent, 42L));
    }

    @Test
    void verifyKitchenAccess_throwsForNonOwner() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);

        assertThrows(UnauthorizedException.class,
                () -> accessService.verifyKitchenAccess(customer, 10L));
    }

    @Test
    void verifyKitchenAccess_throwsWhenNotOwningRestaurant() {
        User owner = ownerUser(5L);
        Restaurant restaurant = restaurantOwnedBy(99L, 10L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertThrows(UnauthorizedException.class,
                () -> accessService.verifyKitchenAccess(owner, 10L));
    }

    @Test
    void verifyKitchenAccess_passesForActualOwner() {
        User owner = ownerUser(5L);
        Restaurant restaurant = restaurantOwnedBy(5L, 10L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertDoesNotThrow(() -> accessService.verifyKitchenAccess(owner, 10L));
    }

    @Test
    void verifyCustomerAccess_throwsForNonCustomer() {
        User agent = userWithRole(User.UserRole.DELIVERY_AGENT, 1L);

        assertThrows(UnauthorizedException.class,
                () -> accessService.verifyCustomerAccess(agent, 42L));
    }

    @Test
    void verifyCustomerAccess_throwsForMissingOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> accessService.verifyCustomerAccess(customer, 42L));
    }

    @Test
    void verifyCustomerAccess_throwsForOthersOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        Order order = orderOwnedBy(2L, 42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertThrows(UnauthorizedException.class,
                () -> accessService.verifyCustomerAccess(customer, 42L));
    }

    @Test
    void verifyCustomerAccess_passesForOwnOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        Order order = orderOwnedBy(1L, 42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

        assertDoesNotThrow(() -> accessService.verifyCustomerAccess(customer, 42L));
    }

    private static Order orderOwnedBy(Long customerId, Long orderId) {
        Customer customer = new Customer();
        customer.setId(customerId);
        Order order = new Order();
        order.setId(orderId);
        order.setCustomer(customer);
        return order;
    }
}
