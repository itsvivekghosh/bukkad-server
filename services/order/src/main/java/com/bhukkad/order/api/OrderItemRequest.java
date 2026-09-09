package com.bhukkad.order.api;

import java.math.BigDecimal;

public record OrderItemRequest(Long menuItemId, String name, BigDecimal unitPrice, int quantity) {
}