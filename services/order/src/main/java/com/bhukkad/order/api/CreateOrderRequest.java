package com.bhukkad.order.api;

import java.math.BigDecimal;
import java.util.List;

/** Order create request (item snapshot during checkout, plan §7). */
public record CreateOrderRequest(Long customerId, Long restaurantId, List<OrderItemRequest> items) {
}