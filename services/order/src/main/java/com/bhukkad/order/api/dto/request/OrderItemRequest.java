package com.bhukkad.order.api.dto.request;

import java.math.BigDecimal;

public record OrderItemRequest(Long menuItemId, String name, BigDecimal unitPrice, int quantity) {
}