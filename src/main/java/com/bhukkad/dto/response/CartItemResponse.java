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
public class CartItemResponse {
    private Long id;
    private Long menuItemId;
    private String menuItemName;
    private Double price;
    private Integer quantity;
    private List<String> customizations;
    private Double totalPrice;
    private String specialInstructions;
}