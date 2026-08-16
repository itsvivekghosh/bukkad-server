package com.bhukkad.dto.request;

import lombok.Data;

import java.util.List;

@Data
public class GroupOrderRequest {
    private Long restaurantId;
    private List<Long> participatingCustomers;
    private Long primaryCustomerId;
    private String specialInstructions;
    private Boolean contactlessDelivery = false;
    private String paymentMethod;
    private Double tipAmount;
}