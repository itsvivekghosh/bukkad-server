package com.bhukkad.live;

import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.User;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderLiveAccessService {

    private final RestaurantRepository restaurantRepository;
    private final OrderRepository orderRepository;

    public boolean canSubscribeKitchen(User user, Long restaurantId) {
        if (user.getRole() != User.UserRole.RESTAURANT_OWNER) {
            return false;
        }
        Restaurant restaurant = restaurantRepository.findByIdWithDetails(restaurantId).orElse(null);
        return restaurant != null && restaurant.getOwner().getId().equals(user.getId());
    }

    public boolean canSubscribeRider(User user, Long agentId) {
        return user.getRole() == User.UserRole.DELIVERY_AGENT && user.getId().equals(agentId);
    }

    public boolean canSubscribeCustomer(User user, Long orderId) {
        if (user.getRole() != User.UserRole.CUSTOMER) {
            return false;
        }
        return orderRepository.findById(orderId)
                .map(order -> order.getCustomer().getId().equals(user.getId()))
                .orElse(false);
    }

    public void verifyKitchenAccess(User user, Long restaurantId) {
        if (!canSubscribeKitchen(user, restaurantId)) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
    }

    public void verifyCustomerAccess(User user, Long orderId) {
        if (!canSubscribeCustomer(user, orderId)) {
            throw new UnauthorizedException("You can only track your own orders");
        }
    }
}
