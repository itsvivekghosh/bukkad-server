package com.bhukkad.order.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.order.api.CreateOrderRequest;
import com.bhukkad.order.api.OrderItemRequest;
import com.bhukkad.order.domain.Order;
import com.bhukkad.order.domain.OrderItem;
import com.bhukkad.order.domain.OrderItemRepository;
import com.bhukkad.order.domain.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Reorder-from-history (port of the monolith's {@code OrderAssistService}):
 * takes the items of a past order and re-adds them to the customer's active
 * cart, so "reorder" is a cart-level operation.
 */
@Service
@RequiredArgsConstructor
public class OrderAssistService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartService cartService;

    @Transactional
    public void reorder(Long customerId, Long pastOrderId) {
        Order past = orderRepository.findById(pastOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + pastOrderId));
        List<OrderItem> items = orderItemRepository.findByOrderId(pastOrderId);
        if (items.isEmpty()) {
            throw new ResourceNotFoundException("No items to reorder from order " + pastOrderId);
        }
        for (OrderItem item : items) {
            cartService.addItem(customerId, item.getMenuItemId(), item.getItemName(),
                    item.getUnitPrice(), item.getQuantity());
        }
    }

    @Transactional(readOnly = true)
    public CreateOrderRequest toCreateRequest(Long pastOrderId) {
        Order past = orderRepository.findById(pastOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + pastOrderId));
        List<OrderItemRequest> items = orderItemRepository.findByOrderId(pastOrderId).stream()
                .map(i -> new OrderItemRequest(i.getMenuItemId(), i.getItemName(),
                        i.getUnitPrice(), i.getQuantity()))
                .toList();
        return new CreateOrderRequest(past.getCustomerId(), past.getRestaurantId(), items);
    }
}