package com.bhukkad.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** Admin operations dashboard 2.0 — settlement pipeline, payouts, delivery ops (V16). */
@Data
@Builder
public class AdminOperationsDashboardResponse {
    private Double totalPendingRestaurantSettlements;
    private Double totalPendingRiderPayouts;
    private Long pendingSettlementCount;
    private Long pendingPayoutCount;
    private Long activeDeliveryBatches;
    private Double todaySettlementVolume;
    private Map<String, Long> ordersByStatus;
    private List<SettlementRunSummary> recentSettlementRuns;
    private EtaAccuracySummary etaAccuracy;

    @Data
    @Builder
    public static class SettlementRunSummary {
        private Long id;
        private String runType;
        private String status;
        private Integer restaurantsSettled;
        private Integer agentsSettled;
        private Double totalAmount;
        private String startedAt;
    }

    /**
     * ETA health for the last 24 hours.
     *
     * <p>The first two fields describe the <em>promise</em> side and come from
     * {@code order_eta_snapshots}: how many ETAs were quoted and how long they were on
     * average. The remaining fields describe <em>accuracy</em> and are aggregated from
     * {@code orders.estimated_delivery_at} versus {@code orders.delivered_at}, since
     * snapshots hold no actual delivery timestamp.
     */
    @Data
    @Builder
    public static class EtaAccuracySummary {
        /** ETA snapshots recorded in the window (promise volume). */
        private Long snapshotsLast24h;
        /** Average quoted ETA in minutes across those snapshots. */
        private Double avgEtaMinutes;
        /** Deliveries in the window that had both a promised and an actual timestamp. */
        private Long measuredDeliveriesLast24h;
        /** Subset of those deliveries that met or beat the promise. */
        private Long onTimeDeliveriesLast24h;
        /** On-time percentage, 0–100, rounded to two decimals; 0 when nothing was measurable. */
        private Double onTimeRatePercent;
        /** Average overshoot in minutes across late deliveries only; 0 when none were late. */
        private Double avgLateMinutes;
    }
}
