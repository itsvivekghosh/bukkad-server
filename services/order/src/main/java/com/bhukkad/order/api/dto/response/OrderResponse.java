package com.bhukkad.order.api.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record OrderResponse(Long id, Long customerId, Long restaurantId, String status,
                            BigDecimal totalAmount, List<OrderItemDto> items) {
}