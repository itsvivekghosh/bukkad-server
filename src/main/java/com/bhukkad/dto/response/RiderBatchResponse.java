package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Multi-stop delivery batch for a rider (V16). */
@Data
@Builder
public class RiderBatchResponse {
    private Long batchId;
    private Long agentId;
    private String status;
    private List<BatchOrderEntry> orders;
    private String createdAt;
    private String completedAt;

    @Data
    @Builder
    public static class BatchOrderEntry {
        private Long orderId;
        private String orderNumber;
        private Integer sequenceNumber;
        private String status;
    }
}
