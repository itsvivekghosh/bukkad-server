package com.bhukkad.mapper;

import com.bhukkad.dto.response.AddressResponse;
import com.bhukkad.dto.response.OrderItemResponse;
import com.bhukkad.dto.response.OrderResponse;
import com.bhukkad.entity.Address;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.entity.Order;
import com.bhukkad.entity.OrderItem;
import com.bhukkad.entity.Payment;
import com.bhukkad.entity.Restaurant;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the MapStruct {@link OrderMapper}. These cover the mapping
 * contract (entity → response) including the qualified-name helpers for the
 * payment enum fields, which are easy to regress silently.
 */
class OrderMapperTest {

    private final OrderMapper orderMapper = Mappers.getMapper(OrderMapper.class);

    private Order sampleOrder() {
        Customer customer = new Customer();
        customer.setId(1L);
        customer.setFullName("Aarav Sharma");

        Restaurant restaurant = new Restaurant();
        restaurant.setId(10L);
        restaurant.setName("Pizza Palace");

        Address address = new Address();
        address.setId(100L);
        address.setAddressLine1("12 MG Road");
        address.setCity("Bangalore");
        address.setPincode("560001");

        MenuItem menuItem = new MenuItem();
        menuItem.setId(200L);
        menuItem.setName("Margherita");
        menuItem.setPrice(299.0);

        OrderItem orderItem = new OrderItem();
        orderItem.setId(300L);
        orderItem.setOrder(new Order());
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(2);
        orderItem.setPrice(299.0);
        orderItem.setSpecialInstructions("extra cheese");

        Order order = new Order();
        order.setId(500L);
        order.setOrderNumber("ORD-ABC12345");
        order.setCustomer(customer);
        order.setRestaurant(restaurant);
        order.setDeliveryAddress(address);
        order.setStatus(Order.OrderStatus.PLACED);
        order.setSubtotal(598.0);
        order.setDeliveryFee(30.0);
        order.setTaxAmount(59.8);
        order.setDiscountAmount(0.0);
        order.setTotalAmount(687.8);
        order.setTipAmount(0.0);
        order.setSpecialInstructions("ring the bell");
        order.setContactlessDelivery(true);
        order.setEstimatedDeliveryTime(35);
        order.setEstimatedDeliveryAt(LocalDateTime.of(2026, 8, 22, 14, 30));
        order.setCreatedAt(LocalDateTime.of(2026, 8, 22, 13, 55));
        order.setOrderItems(java.util.List.of(orderItem));
        return order;
    }

    @Test
    void toResponse_mapsScalarFieldsAndRelationships() {
        OrderResponse response = orderMapper.toResponse(sampleOrder());

        assertEquals(500L, response.getId());
        assertEquals("ORD-ABC12345", response.getOrderNumber());
        assertEquals(1L, response.getCustomerId());
        assertEquals("Aarav Sharma", response.getCustomerName());
        assertEquals(10L, response.getRestaurantId());
        assertEquals("Pizza Palace", response.getRestaurantName());
        assertEquals("PLACED", response.getStatus());
        assertEquals(598.0, response.getSubtotal());
        assertEquals(30.0, response.getDeliveryFee());
        assertEquals(59.8, response.getTaxAmount());
        assertEquals(687.8, response.getTotalAmount());
        assertEquals(35, response.getEstimatedDeliveryTime());
        assertTrue(response.getContactlessDelivery());
        assertEquals("ring the bell", response.getSpecialInstructions());
        assertEquals(LocalDateTime.of(2026, 8, 22, 13, 55), response.getCreatedAt());
    }

    @Test
    void toResponse_mapsOrderItems() {
        OrderResponse response = orderMapper.toResponse(sampleOrder());

        assertNotNull(response.getItems());
        assertEquals(1, response.getItems().size());
        OrderItemResponse item = response.getItems().get(0);
        assertEquals("Margherita", item.getMenuItemName());
        assertEquals(2, item.getQuantity());
        assertEquals(299.0, item.getPrice());
        assertNotNull(item.getCustomizations());
        assertTrue(item.getCustomizations().isEmpty());
    }

    @Test
    void toResponse_mapsDeliveryAddress() {
        OrderResponse response = orderMapper.toResponse(sampleOrder());

        assertNotNull(response.getDeliveryAddress());
        AddressResponse address = response.getDeliveryAddress();
        assertEquals(100L, address.getId());
        assertEquals("12 MG Road", address.getAddressLine1());
        assertEquals("Bangalore", address.getCity());
        assertEquals("560001", address.getPincode());
    }

    @Test
    void toResponse_paymentMethodNames_whenPaymentPresent() {
        Order order = sampleOrder();
        Payment payment = new Payment();
        payment.setPaymentMethod(Payment.PaymentMethod.UPI);
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        order.setPayment(payment);

        OrderResponse response = orderMapper.toResponse(order);
        assertEquals("UPI", response.getPaymentMethod());
        assertEquals("COMPLETED", response.getPaymentStatus());
    }

    @Test
    void toResponse_paymentMethodNull_whenNoPayment() {
        OrderResponse response = orderMapper.toResponse(sampleOrder());

        assertNull(response.getPaymentMethod());
        assertNull(response.getPaymentStatus());
    }

    @Test
    void toItemResponse_mapsMenuItemNameAndEmptyCustomizations() {
        MenuItem menuItem = new MenuItem();
        menuItem.setId(1L);
        menuItem.setName("Butter Chicken");
        menuItem.setPrice(450.0);

        OrderItem orderItem = new OrderItem();
        orderItem.setId(2L);
        orderItem.setMenuItem(menuItem);
        orderItem.setQuantity(1);
        orderItem.setPrice(450.0);

        OrderItemResponse response = orderMapper.toItemResponse(orderItem);
        assertEquals("Butter Chicken", response.getMenuItemName());
        assertEquals(1, response.getQuantity());
        assertEquals(450.0, response.getPrice());
        assertNotNull(response.getCustomizations());
        assertTrue(response.getCustomizations().isEmpty());
    }
}
