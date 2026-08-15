package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Detailed ETA breakdown with confidence band for live tracking (V14). */
@Data
@Builder
public class OrderEtaDetailResponse {
    private Long orderId;
    private Integer etaMinutes;
    private String etaAt;
    private Integer confidenceLowMinutes;
    private Integer confidenceHighMinutes;
    private Double trafficFactor;
    private Double surgeMultiplier;
    private String factorsSummary;
    private List<EtaHistoryEntry> history;

    @Data
    @Builder
    public static class EtaHistoryEntry {
        private Integer etaMinutes;
        private String etaAt;
        private String recordedAt;
    }
}
