package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response for {@code POST /orders/customer/create-batch}.
 *
 * <p>Returns per-restaurant results ({@link BatchOrderResult}) alongside
 * aggregate success/failure counts and a list of human-readable error
 * messages. A {@code batchId} is included so the client can correlate
 * subsequent status queries or retries.</p>
 */
@Data
@Builder
public class BatchOrderResponse {
    private List<BatchOrderResult> orders;
    private int successCount;
    private int failureCount;
    private List<String> errors;
    private String batchId;
}
