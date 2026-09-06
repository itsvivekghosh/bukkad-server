package com.bhukkad.order.api;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class GroupOrderSplitRequest {

    @NotNull(message = "Shares map is required")
    private Map<Long, Double> shares;
}
