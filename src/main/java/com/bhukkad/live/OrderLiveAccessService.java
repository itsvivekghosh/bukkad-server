package com.bhukkad.live;

import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.User;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.order.api.OrderOwnershipPort;
import com.bhukkad.order.api.OrderQueryPort;
import com.bhukkad.repository.RestaurantRepository;
import com.bhukkad.security.LiveSubscriptionAuthorizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Live-stream authorization. Depends on the order domain only through
 * {@link OrderOwnershipPort} / {@link OrderQueryPort} — never the Order entity
 * (modular-monolith boundary; see DomainBoundaryArchTest).
 */
@Service
@RequiredArgsConstructor
public class OrderLiveAccessService implements LiveSubscriptionAuthorizer {

    private final RestaurantRepository restaurantRepository;
    private final OrderOwnershipPort orderOwnershipPort;
    private final OrderQueryPort orderQueryPort;

    public boolean canSubscribeKitchen(User user, Long restaurantId) {
        if (user.getRole() != User.UserRole.RESTAURANT_OWNER) {
            return false;
        }
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId).orElse(null);
        return restaurant != null && restaurant.getOwner() != null && restaurant.getOwner().getId().equals(user.getId());
    }

    public boolean canSubscribeRider(User user, Long agentId) {
        return user.getRole() == User.UserRole.DELIVERY_AGENT && user.getId().equals(agentId);
    }

    public boolean canSubscribeCustomer(User user, Long orderId) {
        if (user.getRole() != User.UserRole.CUSTOMER) {
            return false;
        }
        return orderOwnershipPort.isOwnedByCustomer(orderId, user.getId());
    }

    public void verifyKitchenAccess(User user, Long restaurantId) {
        if (user.getRole() != User.UserRole.RESTAURANT_OWNER) {
            throw new UnauthorizedException("Only restaurant owners can access kitchen stream");
        }
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId).orElse(null);
        if (restaurant == null) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
        if (restaurant.getOwner() == null || !restaurant.getOwner().getId().equals(user.getId())) {
            throw new UnauthorizedException("You don't own this restaurant");
        }
    }

    public void verifyCustomerAccess(User user, Long orderId) {
        if (user.getRole() != User.UserRole.CUSTOMER) {
            throw new UnauthorizedException("Only customers can access order stream");
        }
        if (!orderOwnershipPort.isOwnedByCustomer(orderId, user.getId())) {
            // Distinguish missing order (404) from foreign order (401) exactly
            // like the pre-boundary implementation did.
            if (orderQueryPort.findSummary(orderId).isEmpty()) {
                throw new ResourceNotFoundException("Order not found");
            }
            throw new UnauthorizedException("You can only track your own orders");
        }
    }
}
