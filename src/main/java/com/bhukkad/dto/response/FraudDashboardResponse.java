package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FraudDashboardResponse {
    private long totalEvents;
    private long eventsLast24Hours;
    private long eventsLast7Days;
    private long eventsLast30Days;
    private long pendingReviewCount;
    private Map<String, Long> eventsByType;
    private java.util.List<FraudPatternResponse> topIPs;
    private java.util.List<FraudPatternResponse> topDevices;
    private java.util.List<FraudEventResponse> recentEvents;
}