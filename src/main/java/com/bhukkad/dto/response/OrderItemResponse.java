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
public class OrderItemResponse {
    private Long id;
    private String menuItemName;
    private Integer quantity;
    private Double price;
    private List<String> customizations;
    private String specialInstructions;
}