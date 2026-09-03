package com.bhukkad.live;

import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.entity.User;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.order.api.OrderOwnershipPort;
import com.bhukkad.order.api.OrderQueryPort;
import com.bhukkad.order.api.OrderSummary;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Live authorization against the order domain's {@link OrderOwnershipPort} /
 * {@link OrderQueryPort} seams. The Order entity is never touched here —
 * this test freezes that boundary contract (DomainBoundaryArchTest).
 */
@ExtendWith(MockitoExtension.class)
class OrderLiveAccessServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private OrderOwnershipPort orderOwnershipPort;
    @Mock
    private OrderQueryPort orderQueryPort;

    @InjectMocks
    private OrderLiveAccessService accessService;

    private static User ownerUser(Long id) {
        User user = new User();
        user.setId(id);
        user.setRole(User.UserRole.RESTAURANT_OWNER);
        return user;
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

    @Test
    void canSubscribeKitchen_returnsTrueForOwner() {
        User owner = ownerUser(5L);
        Restaurant restaurant = restaurantOwnedBy(5L, 10L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(restaurant));

        assertTrue(accessService.canSubscribeKitchen(owner, 10L));
    }

    @Test
    void canSubscribeKitchen_returnsFalseForNonOwner() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);

        assertFalse(accessService.canSubscribeKitchen(customer, 10L));
    }

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
    void canSubscribeKitchen_returnsFalseWhenRestaurantMissing() {
        User owner = ownerUser(5L);
        when(restaurantRepository.findByIdWithDetails(10L)).thenReturn(Optional.empty());

        assertFalse(accessService.canSubscribeKitchen(owner, 10L));
    }

    @Test
    void canSubscribeRider_returnsTrueForSelf() {
        User agent = userWithRole(User.UserRole.DELIVERY_AGENT, 6L);

        assertTrue(accessService.canSubscribeRider(agent, 6L));
    }

    @Test
    void canSubscribeRider_returnsFalseForOtherAgent() {
        User agent = userWithRole(User.UserRole.DELIVERY_AGENT, 6L);

        assertFalse(accessService.canSubscribeRider(agent, 7L));
    }

    @Test
    void canSubscribeCustomer_returnsTrueForOwnOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        when(orderOwnershipPort.isOwnedByCustomer(42L, 1L)).thenReturn(true);

        assertTrue(accessService.canSubscribeCustomer(customer, 42L));
    }

    @Test
    void canSubscribeCustomer_returnsFalseForOthersOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        when(orderOwnershipPort.isOwnedByCustomer(42L, 1L)).thenReturn(false);

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
        when(orderOwnershipPort.isOwnedByCustomer(42L, 1L)).thenReturn(false);
        when(orderQueryPort.findSummary(42L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> accessService.verifyCustomerAccess(customer, 42L));
    }

    @Test
    void verifyCustomerAccess_throwsForOthersOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        when(orderOwnershipPort.isOwnedByCustomer(42L, 1L)).thenReturn(false);
        when(orderQueryPort.findSummary(42L)).thenReturn(Optional.of(summary(42L, 2L)));

        assertThrows(UnauthorizedException.class,
                () -> accessService.verifyCustomerAccess(customer, 42L));
    }

    @Test
    void verifyCustomerAccess_passesForOwnOrder() {
        User customer = userWithRole(User.UserRole.CUSTOMER, 1L);
        when(orderOwnershipPort.isOwnedByCustomer(42L, 1L)).thenReturn(true);

        assertDoesNotThrow(() -> accessService.verifyCustomerAccess(customer, 42L));
    }

    private static OrderSummary summary(Long orderId, Long customerId) {
        return new OrderSummary(orderId, "ORD-" + orderId, customerId, 9L, null,
                "PLACED", null, null, null, null, null, null, null, null, null);
    }
}
