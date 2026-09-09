package com.bhukkad.order.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartResponse {
    private Long id;
    private Long customerId;
    private Long restaurantId;
    private List<CartItemResponse> items;
    private Double subtotal;
    private Integer itemCount;
    private String couponCode;
    private Double discountAmount;
}
