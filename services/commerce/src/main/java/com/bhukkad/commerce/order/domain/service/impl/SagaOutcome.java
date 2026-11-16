package com.bhukkad.commerce.order.domain.service.impl;

import com.bhukkad.commerce.order.api.dto.request.OrderItemRequest;
import com.bhukkad.commerce.order.domain.entity.OrderItem;
import java.math.BigDecimal;
import java.util.List;

public record SagaOutcome(String status, String timelineEvent, Long customerId,
                          Long restaurantId, List<OrderItemRequest> items,
                          BigDecimal total, Long orderId) {
}
