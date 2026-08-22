package com.bhukkad.serviceImpl;

import com.bhukkad.cache.OrderCacheService;
import com.bhukkad.delivery.RiderDispatchService;
import com.bhukkad.dto.response.DeliveryAgentResponse;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.User;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.mapper.OrderMapper;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void toggleAvailability_nullFlag_throws() {
        assertThrows(BusinessException.class, () -> deliveryService.toggleAvailability(null));
    }

    @Test
    void findNearestAvailableAgent_noAgents_returnsNull() {
        when(riderDispatchService.findNearestAvailableAgent(12.97, 77.59, java.util.Set.of()))
                .thenReturn(null);

        assertNull(deliveryService.findNearestAvailableAgent(12.97, 77.59));
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
}
