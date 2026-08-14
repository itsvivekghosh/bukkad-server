package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateJobResponse {

    private String jobId;
    private String status;
    private OrderResponse order;
    private String message;
    private String pollUrl;
}
