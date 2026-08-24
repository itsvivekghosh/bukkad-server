package com.bhukkad.delivery;

import com.bhukkad.dto.request.RiderLocationRequest;
import com.bhukkad.dto.response.RiderLocationResponse;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.entity.RiderLocationUpdate;
import com.bhukkad.exception.BusinessException;
import com.bhukkad.exception.ResourceNotFoundException;
import com.bhukkad.live.OrderLiveUpdateBroadcaster;
import com.bhukkad.repository.DeliveryAgentRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RiderLocationUpdateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderLocationServiceTest {

    @Mock
    private RiderLocationUpdateRepository riderLocationUpdateRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private DeliveryAgentRepository deliveryAgentRepository;
    @Mock
    private OrderEtaService orderEtaService;
    @Mock
    private OrderLiveUpdateBroadcaster orderLiveUpdateBroadcaster;

    @InjectMocks
    private RiderLocationService service;

    private Order order;
    private DeliveryAgent agent;
    private RiderLocationRequest request;

    @BeforeEach
    void setUp() {
        agent = new DeliveryAgent();
        agent.setId(9L);

        Customer customer = new Customer();
        customer.setId(3L);

        Restaurant restaurant = new Restaurant();
        restaurant.setId(4L);

        order = new Order();
        order.setId(1L);
        order.setDeliveryAgent(agent);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);

        request = new RiderLocationRequest();
        request.setLatitude(12.9716);
        request.setLongitude(77.5946);
    }

    @Test
    void recordLocation_savesUpdateAndBroadcasts() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(riderLocationUpdateRepository.save(any(RiderLocationUpdate.class))).thenAnswer(inv -> inv.getArgument(0));

        RiderLocationResponse result = service.recordLocation(1L, request);

        assertEquals(1L, result.getOrderId());
        assertEquals(9L, result.getAgentId());
        assertEquals(12.9716, result.getLatitude());
        verify(deliveryAgentRepository).save(agent);
        verify(orderEtaService).applyLiveEta(order);
        verify(orderRepository).save(order);
        verify(orderLiveUpdateBroadcaster).broadcastRiderLocation(1L, 3L, 4L, 9L, 12.9716, 77.5946);
    }

    @Test
    void recordLocation_throws_whenOrderNotFound() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.recordLocation(99L, request));
    }

    @Test
    void recordLocation_throws_whenNoAgentAssigned() {
        order.setDeliveryAgent(null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        assertThrows(BusinessException.class, () -> service.recordLocation(1L, request));
    }

    @Test
    void getLatestForOrder_returnsLocation() {
        RiderLocationUpdate update = new RiderLocationUpdate();
        update.setOrder(order);
        update.setAgent(agent);
        update.setLatitude(12.9);
        update.setLongitude(77.5);

        when(riderLocationUpdateRepository.findFirstByOrderIdOrderByRecordedAtDesc(1L))
                .thenReturn(Optional.of(update));

        RiderLocationResponse result = service.getLatestForOrder(1L);
        assertEquals(1L, result.getOrderId());
        assertEquals(12.9, result.getLatitude());
    }

    @Test
    void getLatestForOrder_throws_whenNone() {
        when(riderLocationUpdateRepository.findFirstByOrderIdOrderByRecordedAtDesc(1L))
                .thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.getLatestForOrder(1L));
    }
}