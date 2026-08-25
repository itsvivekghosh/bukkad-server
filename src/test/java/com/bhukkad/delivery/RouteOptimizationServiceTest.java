package com.bhukkad.delivery;

import com.bhukkad.entity.Address;
import com.bhukkad.entity.Order;
import com.bhukkad.repository.AddressRepository;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RouteOptimizationServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private AddressRepository addressRepository;

    private RouteOptimizationService service;

    @BeforeEach
    void setUp() {
        service = new RouteOptimizationService(orderRepository, addressRepository);
    }

    private Order orderWithAddress(Long id, Long addressId) {
        Order order = new Order();
        order.setId(id);
        if (addressId != null) {
            Address ref = new Address();
            ref.setId(addressId);
            order.setDeliveryAddress(ref);
        }
        return order;
    }

    private Address address(Long id, Double lat, Double lng) {
        Address address = new Address();
        address.setId(id);
        address.setLatitude(lat);
        address.setLongitude(lng);
        return address;
    }

    @Test
    void optimizeStops_nullList_returnsEmpty() {
        assertTrue(service.optimizeStops(null, 1L, 10.0, 10.0).isEmpty());
    }

    @Test
    void optimizeStops_emptyList_returnsEmpty() {
        assertTrue(service.optimizeStops(List.of(), 1L, 10.0, 10.0).isEmpty());
    }

    @Test
    void optimizeStops_singleOrder_returnsAsIs() {
        List<Long> result = service.optimizeStops(List.of(5L), 1L, 10.0, 10.0);
        assertEquals(List.of(5L), result);
    }

    @Test
    void optimizeStops_missingLocation_returnsOriginalOrder() {
        List<Long> result = service.optimizeStops(List.of(1L, 2L), 1L, null, null);
        assertEquals(List.of(1L, 2L), result);
        verify(orderRepository, never()).findById(anyLong());
    }

    @Test
    void optimizeStops_missingOrder_fallsBackToOriginal() {
        when(orderRepository.findById(1L)).thenReturn(Optional.empty());

        List<Long> result = service.optimizeStops(List.of(1L, 2L), 1L, 10.0, 10.0);

        assertEquals(List.of(1L, 2L), result);
    }

    @Test
    void optimizeStops_orderWithoutAddress_fallsBackToOriginal() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderWithAddress(1L, null)));

        List<Long> result = service.optimizeStops(List.of(1L, 2L), 1L, 10.0, 10.0);

        assertEquals(List.of(1L, 2L), result);
    }

    @Test
    void optimizeStops_addressWithoutCoords_fallsBackToOriginal() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderWithAddress(1L, 11L)));
        when(addressRepository.findById(11L)).thenReturn(Optional.of(address(11L, null, null)));

        List<Long> result = service.optimizeStops(List.of(1L, 2L), 1L, 10.0, 10.0);

        assertEquals(List.of(1L, 2L), result);
    }

    @Test
    void optimizeStops_reordersStopsNearestFirst() {
        // Start at (0,0). Order 1 at (1,1) is closest, order 2 at (50,50) far.
        when(orderRepository.findById(1L)).thenReturn(Optional.of(orderWithAddress(1L, 11L)));
        when(orderRepository.findById(2L)).thenReturn(Optional.of(orderWithAddress(2L, 22L)));
        when(addressRepository.findById(11L)).thenReturn(Optional.of(address(11L, 1.0, 1.0)));
        when(addressRepository.findById(22L)).thenReturn(Optional.of(address(22L, 50.0, 50.0)));

        List<Long> result = service.optimizeStops(List.of(2L, 1L), 1L, 0.0, 0.0);

        // Nearest (1) visited first even though it was listed second
        assertEquals(List.of(1L, 2L), result);
    }
}