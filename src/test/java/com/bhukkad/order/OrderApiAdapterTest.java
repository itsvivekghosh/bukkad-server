package com.bhukkad.order;

import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.DeliveryAgent;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.Restaurant;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.OrderQueryPort;
import com.bhukkad.order.api.OrderSummary;
import com.bhukkad.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the cross-domain order seam. When the order service is
 * physically extracted (Phase 3), {@link OrderApiAdapter} becomes an HTTP/gRPC
 * client and these tests assert the same {@link OrderSummary} shape against it.
 */
@ExtendWith(MockitoExtension.class)
class OrderApiAdapterTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderApiAdapter adapter;

    @Test
    void findSummary_mapsFullProjection() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L)));

        OrderSummary summary = adapter.findSummary(42L).orElseThrow();

        assertEquals(42L, summary.id());
        assertEquals("ORD-42", summary.orderNumber());
        assertEquals(7L, summary.customerId());
        assertEquals(9L, summary.restaurantId());
        assertEquals(13L, summary.deliveryAgentId());
        assertEquals("OUT_FOR_DELIVERY", summary.status());
        assertEquals(0, new java.math.BigDecimal("599.50").compareTo(summary.totalAmount()));
        assertEquals(0, new java.math.BigDecimal("549.50").compareTo(summary.subtotal()));
        assertEquals(0, new java.math.BigDecimal("20.00").compareTo(summary.tipAmount()));
        assertEquals(50, summary.loyaltyPointsRedeemed());
        assertNotNull(summary.deliveryAddress());
        assertEquals(5L, summary.deliveryAddress().id());
        assertEquals("MG Road", summary.deliveryAddress().addressLine1());
        assertEquals("Bengaluru", summary.deliveryAddress().city());
        assertEquals("560001", summary.deliveryAddress().pincode());
        assertEquals(12.97, summary.deliveryAddress().latitude());
        assertEquals(77.59, summary.deliveryAddress().longitude());
        assertEquals(createdAt, summary.createdAt());
        assertEquals(scheduledAt, summary.scheduledAt());
        assertEquals(12, summary.liveEtaMinutes());
        assertEquals(scheduledAt, summary.liveEtaAt());
    }

    @Test
    void findSummary_nullsAreMappedToNulls() {
        Order bare = new Order();
        bare.setId(1L);
        bare.setStatus(null);
        when(orderRepository.findById(1L)).thenReturn(Optional.of(bare));

        OrderSummary summary = adapter.findSummary(1L).orElseThrow();

        assertNull(summary.orderNumber());
        assertNull(summary.customerId());
        assertNull(summary.restaurantId());
        assertNull(summary.deliveryAgentId());
        assertNull(summary.status());
        assertNull(summary.totalAmount());
        assertNull(summary.subtotal());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(summary.tipAmount())); // entity default
        assertEquals(0, summary.loyaltyPointsRedeemed());
        assertNull(summary.deliveryAddress());
        assertNull(summary.createdAt());
        assertNull(summary.liveEtaMinutes());
        assertNull(summary.liveEtaAt());
    }

    @Test
    void findSummary_returnsEmptyForMissingOrder() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        assertTrue(adapter.findSummary(99L).isEmpty());
    }

    @Test
    void requireSummary_returnsSummaryWhenPresent() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L)));

        assertEquals(42L, adapter.requireSummary(42L).id());
    }

    @Test
    void requireSummary_throwsResourceNotFoundForMissingOrder() {
        when(orderRepository.findById(99L)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adapter.requireSummary(99L));
        assertEquals("Order not found", ex.getMessage());
    }

    @Test
    void isOwnedByCustomer_trueForOwner() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L)));

        assertTrue(adapter.isOwnedByCustomer(42L, 7L));
    }

    @Test
    void isOwnedByCustomer_falseForDifferentCustomer() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L)));

        assertFalse(adapter.isOwnedByCustomer(42L, 8L));
    }

    @Test
    void isOwnedByCustomer_falseForMissingOrder() {
        when(orderRepository.findById(42L)).thenReturn(Optional.empty());

        assertFalse(adapter.isOwnedByCustomer(42L, 7L));
    }

    @Test
    void adapterImplementsBothPorts() {
        assertTrue(adapter instanceof OrderQueryPort);
        assertTrue(adapter instanceof com.bhukkad.order.api.OrderOwnershipPort);
    }

    private final LocalDateTime createdAt = LocalDateTime.of(2026, 8, 29, 12, 0);
    private final LocalDateTime scheduledAt = LocalDateTime.of(2026, 8, 29, 13, 30);

    private Order order(Long id) {
        Customer customer = new Customer();
        customer.setId(7L);
        Restaurant restaurant = new Restaurant();
        restaurant.setId(9L);
        DeliveryAgent agent = new DeliveryAgent();
        agent.setId(13L);
        Address address = new Address();
        address.setId(5L);
        address.setAddressLine1("MG Road");
        address.setCity("Bengaluru");
        address.setPincode("560001");
        address.setLatitude(12.97);
        address.setLongitude(77.59);

        Order order = new Order();
        order.setId(id);
        order.setOrderNumber("ORD-" + id);
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAgent(agent);
        order.setStatus(Order.OrderStatus.OUT_FOR_DELIVERY);
        order.setSubtotal(549.50);
        order.setTotalAmount(599.50);
        order.setTipAmount(20.00);
        order.setLoyaltyPointsRedeemed(50);
        order.setDeliveryAddress(address);
        order.setCreatedAt(createdAt);
        order.setScheduledAt(scheduledAt);
        order.setLiveEtaMinutes(12);
        order.setLiveEtaAt(scheduledAt);
        return order;
    }
}
