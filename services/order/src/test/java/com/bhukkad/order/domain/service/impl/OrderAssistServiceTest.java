package com.bhukkad.order.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.order.domain.entity.Order;
import com.bhukkad.order.domain.entity.OrderItem;
import com.bhukkad.order.domain.repository.OrderItemRepository;
import com.bhukkad.order.domain.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderAssistServiceTest {

    @Mock private OrderRepository orderRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private CartService cartService;
    @InjectMocks private OrderAssistService service;

    private Order order() {
        Order o = new Order();
        o.setId(1L);
        o.setCustomerId(7L);
        o.setRestaurantId(2L);
        return o;
    }

    private OrderItem item(Long menuItemId, String name, BigDecimal price, int qty) {
        OrderItem i = new OrderItem();
        i.setMenuItemId(menuItemId);
        i.setItemName(name);
        i.setUnitPrice(price);
        i.setQuantity(qty);
        return i;
    }

    @Test
    void reorder_reAddsItemsToCart() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order()));
        when(orderItemRepository.findByOrderId(1L)).thenReturn(
                List.of(item(100L, "Paneer", new BigDecimal("240.00"), 2)));

        service.reorder(7L, 1L);

        verify(cartService).addItem(7L, 100L, "Paneer", new BigDecimal("240.00"), 2);
    }

    @Test
    void reorder_unknownOrder_throws() {
        when(orderRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reorder(7L, 9L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reorder_emptyItems_throws() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order()));
        when(orderItemRepository.findByOrderId(1L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.reorder(7L, 1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No items to reorder");
    }

    @Test
    void toCreateRequest_mapsItems() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order()));
        when(orderItemRepository.findByOrderId(1L)).thenReturn(
                List.of(item(100L, "Paneer", new BigDecimal("240.00"), 2)));

        CreateOrderRequest request = service.toCreateRequest(1L);

        assertThat(request.customerId()).isEqualTo(7L);
        assertThat(request.restaurantId()).isEqualTo(2L);
        assertThat(request.items()).hasSize(1);
        assertThat(request.items().get(0).name()).isEqualTo("Paneer");
    }
}
