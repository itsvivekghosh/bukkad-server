package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.commerce.order.api.dto.request.CreateOrderRequest;
import com.bhukkad.commerce.order.api.dto.request.OrderItemRequest;
import com.bhukkad.commerce.order.api.dto.response.OrderResponse;
import com.bhukkad.commerce.order.domain.entity.Order;
import com.bhukkad.commerce.order.domain.entity.OrderItem;
import com.bhukkad.commerce.order.domain.repository.OrderItemRepository;
import com.bhukkad.commerce.order.domain.repository.OrderRepository;
import com.bhukkad.commerce.order.domain.service.impl.OrderCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReorderService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderCreationService orderCreationService;

    @Transactional
    public OrderResponse reorder(Long customerId, Long orderId) {
        Order original = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));
        if (!original.getCustomerId().equals(customerId)) {
            throw new BusinessException("Cannot reorder another customer's order");
        }
        List<OrderItemRequest> items = orderItemRepository.findByOrderId(orderId).stream()
                .map(i -> new OrderItemRequest(i.getMenuItemId(), i.getItemName(),
                        i.getUnitPrice(), i.getQuantity()))
                .toList();
        if (items.isEmpty()) {
            throw new BusinessException("Original order has no items to reorder");
        }
        return orderCreationService.createOrder(
                new CreateOrderRequest(customerId, original.getRestaurantId(), items));
    }
}
