package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class BatchOrderResponse {
    private List<OrderResponse> orders;
    private int successCount;
    private int failureCount;
    private List<String> errors;
}
