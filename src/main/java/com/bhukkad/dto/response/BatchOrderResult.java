package com.bhukkad.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-restaurant result within a {@link BatchOrderResponse}.
 *
 * <p>Each restaurant's order is placed independently, so a batch can have
 * mixed success/failure outcomes. This result carries the order (when
 * successful) or an error message (when failed), alongside the
 * restaurant identity so the client can show per-restaurant status.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchOrderResult {
    private Long orderId;
    private String orderNumber;
    private Long restaurantId;
    private String restaurantName;
    private String status;
    private Double totalAmount;
    private boolean success;
    private String errorMessage;
}
