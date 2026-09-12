package com.bhukkad.order.api.dto.request;

import java.math.BigDecimal;
import java.util.List;
import com.bhukkad.order.domain.entity.Order;

/** Order create request (item snapshot during checkout, plan §7). */
public record CreateOrderRequest(Long customerId, Long restaurantId, List<OrderItemRequest> items) {
}