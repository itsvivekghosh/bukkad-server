package com.bhukkad.dto.response;

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
    private Long restaurantId;
    private String restaurantName;
    private List<CartItemResponse> items;
    private Double subtotal;
    private Integer itemCount;
}