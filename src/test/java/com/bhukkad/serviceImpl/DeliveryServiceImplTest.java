package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.dto.request.UpdateDeliveryProfileRequest;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.exception.UnauthorizedException;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceImplTest {

    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private SecurityUtils securityUtils;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private com.bhukkad.event.OrderEventPublisher orderEventPublisher;
    @Mock
    private OrderCacheService orderCacheService;
    @Mock
    private RiderDispatchService riderDispatchService;

    @InjectMocks
    private DeliveryServiceImpl deliveryService;

    @Test
    void getProfile_agentNotFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.getProfile());
        assertEquals("Agent not found", ex.getMessage());
    }

    @Test
    void getProfile_mapsAllFields() {
        DeliveryAgent agent = agent(5L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));

        DeliveryAgentResponse response = deliveryService.getProfile();

        assertEquals(5L, response.getId());
        assertEquals("Ravi Kumar", response.getFullName());
        assertEquals("DELIVERY_AGENT", response.getRole());
    }

    @Test
    void getCurrentDeliveryAgent_returnsAgent() {
        DeliveryAgent agent = agent(5L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));

        assertEquals(5L, deliveryService.getCurrentDeliveryAgent().getId());
    }

    @Test
    void getCurrentDeliveryAgent_notFound_throws() {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.getCurrentDeliveryAgent());
        assertEquals("Agent not found", ex.getMessage());
    }

    @Test
    void getDeliveryAgentById_found_returnsAgent() {
        DeliveryAgent agent = agent(9L);
        when(deliveryAgentRepository.findById(9L)).thenReturn(Optional.of(agent));

        assertEquals(9L, deliveryService.getDeliveryAgentById(9L).getId());
    }

    @Test
    void getDeliveryAgentById_notFound_throws() {
        when(deliveryAgentRepository.findById(9L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.getDeliveryAgentById(9L));
        assertEquals("Agent not found", ex.getMessage());
    }

    @Test
    void updateProfile_updatesProvidedFields() {
        DeliveryAgent existing = agent(5L);
        UpdateDeliveryProfileRequest updates = new UpdateDeliveryProfileRequest();
        updates.setFullName("New Name");
        updates.setPhoneNumber("1111111111");
        updates.setVehicleType("SCOOTER");
        updates.setVehicleNumber("KA02CD5678");
        updates.setLicenseNumber("LIC-NEW");

        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.save(existing)).thenReturn(existing);

        DeliveryAgentResponse response = deliveryService.updateProfile(5L, updates);

        assertEquals("New Name", existing.getFullName());
        assertEquals("1111111111", existing.getPhoneNumber());
        assertEquals("SCOOTER", existing.getVehicleType());
        assertEquals("KA02CD5678", existing.getVehicleNumber());
        assertEquals("LIC-NEW", existing.getLicenseNumber());
        assertEquals(5L, response.getId());
    }

    @Test
    void updateProfile_blankFieldsAreIgnored() {
        DeliveryAgent existing = agent(5L);
        UpdateDeliveryProfileRequest updates = new UpdateDeliveryProfileRequest();
        updates.setFullName("  ");
        updates.setPhoneNumber("");
        updates.setVehicleType(null);

        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.save(existing)).thenReturn(existing);

        deliveryService.updateProfile(5L, updates);

        assertEquals("Ravi Kumar", existing.getFullName());
        assertEquals("9876543210", existing.getPhoneNumber());
        assertEquals("BIKE", existing.getVehicleType());
    }

    @Test
    void updateProfile_anotherAgentsProfile_throwsUnauthorized() {
        DeliveryAgent existing = agent(9L);
        when(deliveryAgentRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(securityUtils.getCurrentUserId()).thenReturn(5L);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> deliveryService.updateProfile(9L, new UpdateDeliveryProfileRequest()));
        assertEquals("Cannot update another agent's profile", ex.getMessage());
    }

    @Test
    void toggleAvailability_flagPersisted() {
        DeliveryAgent agent = agent(5L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(deliveryAgentRepository.save(agent)).thenReturn(agent);

        deliveryService.toggleAvailability(false);

        assertEquals(false, agent.getAvailable());
        verify(deliveryAgentRepository).save(agent);
    }

    @Test
    void toggleAvailability_trueFlagPersisted() {
        DeliveryAgent agent = agent(5L);
        agent.setAvailable(false);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(deliveryAgentRepository.save(agent)).thenReturn(agent);

        deliveryService.toggleAvailability(true);

        assertEquals(true, agent.getAvailable());
        verify(deliveryAgentRepository).save(agent);
    }

    @Test
    void toggleAvailability_nullFlag_throws() {
        assertThrows(BusinessException.class, () -> deliveryService.toggleAvailability(null));
    }

    @Test
    void updateLocation_updatesCoordinates() {
        DeliveryAgent agent = agent(5L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(deliveryAgentRepository.save(agent)).thenReturn(agent);

        deliveryService.updateLocation(12.97, 77.59);

        assertEquals(12.97, agent.getCurrentLatitude());
        assertEquals(77.59, agent.getCurrentLongitude());
        verify(deliveryAgentRepository).save(agent);
    }

    @Test
    void updateLocation_nullLatitude_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.updateLocation(null, 77.59));
        assertEquals("Latitude and longitude are required", ex.getMessage());
    }

    @Test
    void updateLocation_nullLongitude_throws() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.updateLocation(12.97, null));
        assertEquals("Latitude and longitude are required", ex.getMessage());
    }

    @Test
    void getAvailableOrders_notAvailable_returnsEmptyList() {
        DeliveryAgent agent = agent(5L);
        agent.setAvailable(false);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));

        assertTrue(deliveryService.getAvailableOrders().isEmpty());
        verify(orderRepository, never()).findAvailableDeliveriesForAgent(any(), any());
    }

    @Test
    void getAvailableOrders_available_mapsOrders() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        OrderResponse response = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findAvailableDeliveriesForAgent(5L, Order.OrderStatus.READY_FOR_PICKUP))
                .thenReturn(List.of(order));
        when(orderMapper.toResponse(order)).thenReturn(response);

        List<OrderResponse> result = deliveryService.getAvailableOrders();

        assertEquals(List.of(response), result);
    }

    @Test
    void getActiveDeliveries_mapsOrders() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        OrderResponse response = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByDeliveryAgentIdAndStatusIn(eq(5L), any()))
                .thenReturn(List.of(order));
        when(orderMapper.toResponse(order)).thenReturn(response);

        List<OrderResponse> result = deliveryService.getActiveDeliveries();

        assertEquals(List.of(response), result);
    }

    @Test
    void getDeliveryHistory_mapsOrders() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        OrderResponse response = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByDeliveryAgentIdAndStatus(5L, Order.OrderStatus.DELIVERED))
                .thenReturn(List.of(order));
        when(orderMapper.toResponse(order)).thenReturn(response);

        List<OrderResponse> result = deliveryService.getDeliveryHistory();

        assertEquals(List.of(response), result);
    }

    @Test
    void acceptDelivery_agentNotAvailable_throws() {
        DeliveryAgent agent = agent(5L);
        agent.setAvailable(false);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.acceptDelivery(1L));
        assertEquals("Mark yourself available before accepting deliveries", ex.getMessage());
    }

    @Test
    void acceptDelivery_agentNotVerified_throws() {
        DeliveryAgent agent = agent(5L);
        agent.setVerified(false);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.acceptDelivery(1L));
        assertEquals("Agent account is not verified", ex.getMessage());
    }

    @Test
    void acceptDelivery_orderNotFound_throws() {
        DeliveryAgent agent = agent(5L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.acceptDelivery(1L));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void acceptDelivery_orderNotReady_throws() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        order.setStatus(Order.OrderStatus.PREPARING);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.acceptDelivery(1L));
        assertEquals("Order is not ready for pickup", ex.getMessage());
    }

    @Test
    void acceptDelivery_assignedToAnotherAgent_throws() {
        DeliveryAgent agent = agent(5L);
        DeliveryAgent other = new DeliveryAgent();
        other.setId(9L);
        Order order = order(1L);
        order.setDeliveryAgent(other);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.acceptDelivery(1L));
        assertEquals("Order is assigned to another agent", ex.getMessage());
    }

    @Test
    void acceptDelivery_success_assignsAndPublishes() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        OrderResponse response = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderMapper.toResponse(order)).thenReturn(response);

        OrderResponse result = deliveryService.acceptDelivery(1L);

        assertEquals(response, result);
        assertEquals(agent, order.getDeliveryAgent());
        verify(orderCacheService).invalidateOrder(1L, 100L, 200L);
        verify(orderEventPublisher).publishAgentAssigned(order);
    }

    @Test
    void rejectDelivery_orderNotFound_throws() {
        DeliveryAgent agent = agent(5L);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> deliveryService.rejectDelivery(1L));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void rejectDelivery_notAssignedToAgent_throws() {
        DeliveryAgent agent = agent(5L);
        DeliveryAgent other = new DeliveryAgent();
        other.setId(9L);
        Order order = order(1L);
        order.setDeliveryAgent(other);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.rejectDelivery(1L));
        assertEquals("Order is not assigned to you", ex.getMessage());
    }

    @Test
    void rejectDelivery_outForDelivery_throws() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        order.setStatus(Order.OrderStatus.OUT_FOR_DELIVERY);
        order.setDeliveryAgent(agent);
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> deliveryService.rejectDelivery(1L));
        assertEquals("Cannot reject an order already out for delivery", ex.getMessage());
    }

    @Test
    void rejectDelivery_success_releasesOrder() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        order.setDeliveryAgent(agent);
        OrderResponse response = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(riderDispatchService.autoAssignNearestRider(order, Set.of(5L))).thenReturn(Optional.empty());
        when(orderMapper.toResponse(order)).thenReturn(response);

        OrderResponse result = deliveryService.rejectDelivery(1L);

        assertEquals(response, result);
        assertNull(order.getDeliveryAgent());
        verify(orderCacheService).invalidateOrder(1L, 100L, 200L);
    }

    @Test
    void rejectDelivery_success_returnsReassignedOrder() {
        DeliveryAgent agent = agent(5L);
        Order order = order(1L);
        order.setDeliveryAgent(agent);
        Order reassigned = order(1L);
        reassigned.setDeliveryAgent(new DeliveryAgent());
        reassigned.getDeliveryAgent().setId(9L);
        OrderResponse response = new OrderResponse();
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(deliveryAgentRepository.findById(5L)).thenReturn(Optional.of(agent));
        when(orderRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(riderDispatchService.autoAssignNearestRider(order, Set.of(5L))).thenReturn(Optional.of(reassigned));
        when(orderMapper.toResponse(reassigned)).thenReturn(response);

        OrderResponse result = deliveryService.rejectDelivery(1L);

        assertEquals(response, result);
        verify(orderCacheService).invalidateOrder(1L, 100L, 200L);
    }

    @Test
    void getAllDeliveryAgents_mapsAll() {
        DeliveryAgent agent = agent(5L);
        // Batch A/B pagination: the service now pages through the fleet to avoid OOM.
        when(deliveryAgentRepository.findAll(org.springframework.data.domain.Pageable.ofSize(200)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(agent)));

        List<DeliveryAgentResponse> result = deliveryService.getAllDeliveryAgents();

        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).getId());
    }

    @Test
    void findNearestAvailableAgent_noAgents_returnsNull() {
        when(riderDispatchService.findNearestAvailableAgent(12.97, 77.59, java.util.Set.of()))
                .thenReturn(null);

        assertNull(deliveryService.findNearestAvailableAgent(12.97, 77.59));
    }

    @Test
    void findNearestAvailableAgent_returnsAgent() {
        DeliveryAgent agent = agent(7L);
        when(riderDispatchService.findNearestAvailableAgent(12.97, 77.59, java.util.Set.of()))
                .thenReturn(agent);

        assertEquals(7L, deliveryService.findNearestAvailableAgent(12.97, 77.59).getId());
    }

    private DeliveryAgent agent(Long id) {
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(id);
        agent.setFullName("Ravi Kumar");
        agent.setEmail("ravi@bhukkad.com");
        agent.setPhoneNumber("9876543210");
        agent.setVehicleType("BIKE");
        agent.setVehicleNumber("KA01AB1234");
        agent.setAvailable(true);
        agent.setVerified(true);
        agent.setAverageRating(4.8);
        agent.setTotalDeliveries(120);
        agent.setRole(User.UserRole.DELIVERY_AGENT);
        agent.setCurrentLatitude(12.97);
        agent.setCurrentLongitude(77.59);
        return agent;
    }

    private Order order(Long id) {
        Customer customer = new Customer();
        customer.setId(100L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(200L);
        Order order = new Order();
        order.setId(id);
        order.setStatus(Order.OrderStatus.READY_FOR_PICKUP);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        return order;
    }
}
