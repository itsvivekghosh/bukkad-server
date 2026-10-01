package com.bhukkad.commerce.order.api.dto.response;

import java.math.BigDecimal;

public record OrderItemDto(Long menuItemId, String name, BigDecimal unitPrice, int quantity) {
}