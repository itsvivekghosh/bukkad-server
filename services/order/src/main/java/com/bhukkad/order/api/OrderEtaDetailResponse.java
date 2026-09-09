package com.bhukkad.order.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderEtaDetailResponse {
    private Long orderId;
    private Integer etaMinutes;
    private LocalDateTime etaAt;
    private Integer actualMinutes;
    private Integer confidenceLowMinutes;
    private Integer confidenceHighMinutes;
    private BigDecimal trafficFactor;
    private BigDecimal surgeMultiplier;
    private String factorsSummary;
    private List<EtaHistoryEntry> history;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EtaHistoryEntry {
        private Integer etaMinutes;
        private LocalDateTime etaAt;
        private LocalDateTime recordedAt;
    }
}
