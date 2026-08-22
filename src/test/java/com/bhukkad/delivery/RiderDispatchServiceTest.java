package com.bhukkad.delivery;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.RoadDistanceService;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RestaurantOwner;
import com.bhukkad.event.OrderEventPublisher;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderDispatchServiceTest {

    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderEventPublisher orderEventPublisher;
    @Mock
    private OrderCacheService orderCacheService;
    @Mock
    private RoadDistanceService roadDistanceService;

    @InjectMocks
    private RiderDispatchService riderDispatchService;

    @Test
    void autoAssignNearestRider_assignsAndPublishesEvent() {
        Order order = readyOrder();
        DeliveryAgent agent = agent(7L);

        when(deliveryAgentRepository.findAvailableAgents()).thenReturn(List.of(agent));
        when(roadDistanceService.route(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new RoadDistanceService.RoadRoute(1.5, 3.0, false));
        when(orderRepository.save(order)).thenReturn(order);

        Optional<Order> assigned = riderDispatchService.autoAssignNearestRider(order);

        assertTrue(assigned.isPresent());
        verify(orderEventPublisher).publishAgentAssigned(order);
    }

    private DeliveryAgent agent(Long id) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(id);
        agent.setAvailable(true);
        agent.setVerified(true);
        agent.setCurrentLatitude(12.97);
        agent.setCurrentLongitude(77.59);
        return agent;
    }

    private Order readyOrder() {
        RestaurantOwner owner = new RestaurantOwner();
        owner.setId(9L);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        restaurant.setOwner(owner);

        Customer customer = new Customer();
        customer.setId(1L);

        Address address = new Address();
        address.setLatitude(12.971);
        address.setLongitude(77.594);

        Order order = new Order();
        order.setId(5L);
        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);
        order.setRestaurant(restaurant);
        order.setCustomer(customer);
        order.setDeliveryAddress(address);
        return order;
    }
}
