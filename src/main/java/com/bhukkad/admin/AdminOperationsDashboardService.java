package com.bhukkad.admin;

import com.bhukkad.dto.response.AdminOperationsDashboardResponse;
import com.bhukkad.entity.RestaurantSettlement;
import com.bhukkad.entity.RiderDeliveryBatch;
import com.bhukkad.entity.RiderEarning;
import com.bhukkad.entity.SettlementRun;
import com.bhukkad.repository.OrderEtaSnapshotRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantSettlementRepository;
import com.bhukkad.repository.RiderDeliveryBatchRepository;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.repository.SettlementRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin operations dashboard 2.0 with settlement pipeline and delivery ops metrics (V16).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminOperationsDashboardService {

    /**
     * Upper bound on late deliveries pulled back to average their overshoot. The
     * dashboard only needs a representative figure, and this keeps a bad day from
     * loading an unbounded row set into memory.
     */
    private static final int LATE_SAMPLE_LIMIT = 500;

    private final RestaurantSettlementRepository settlementRepository;
    private final RiderEarningRepository riderEarningRepository;
    private final SettlementRunRepository settlementRunRepository;
    private final RiderDeliveryBatchRepository batchRepository;
    private final OrderRepository orderRepository;
    private final OrderEtaSnapshotRepository etaSnapshotRepository;

    public AdminOperationsDashboardResponse getDashboard() {
        Double pendingRestaurant = settlementRepository.sumNetAmountByStatus(
                RestaurantSettlement.SettlementStatus.PENDING);
        Double pendingRider = riderEarningRepository.sumAmountByStatus(RiderEarning.EarningStatus.PENDING);
        long pendingSettlementCount = settlementRepository.countByStatus(
                RestaurantSettlement.SettlementStatus.PENDING);
        long pendingPayoutCount = riderEarningRepository.countByStatus(RiderEarning.EarningStatus.PENDING);
        long activeBatches = batchRepository.findAll().stream()
                .filter(b -> b.getStatus() == RiderDeliveryBatch.BatchStatus.ACTIVE)
                .count();

        Map<String, Long> ordersByStatus = new LinkedHashMap<>();
        for (com.bhukkad.entity.Order.OrderStatus status : com.bhukkad.entity.Order.OrderStatus.values()) {
            ordersByStatus.put(status.name(), orderRepository.countByStatus(status));
        }

        Double todayVolume = settlementRepository.sumNetAmountByStatus(
                RestaurantSettlement.SettlementStatus.SETTLED);

        LocalDateTime last24h = LocalDateTime.now().minusHours(24);
        var recentRuns = settlementRunRepository.findTop10ByOrderByStartedAtDesc().stream()
                .limit(5)
                .map(this::toRunSummary)
                .toList();

        return AdminOperationsDashboardResponse.builder()
                .totalPendingRestaurantSettlements(pendingRestaurant != null ? pendingRestaurant : 0.0)
                .totalPendingRiderPayouts(pendingRider != null ? pendingRider : 0.0)
                .pendingSettlementCount(pendingSettlementCount)
                .pendingPayoutCount(pendingPayoutCount)
                .activeDeliveryBatches(activeBatches)
                .todaySettlementVolume(todayVolume != null ? todayVolume : 0.0)
                .ordersByStatus(ordersByStatus)
                .recentSettlementRuns(recentRuns)
                .etaAccuracy(buildEtaAccuracy(last24h))
                .build();
    }

    /**
     * Assembles the ETA panel: promise volume and average quoted ETA from
     * {@code order_eta_snapshots}, plus promised-vs-actual accuracy aggregated from
     * {@code orders.estimated_delivery_at} against {@code orders.delivered_at}.
     *
     * <p>Snapshots record only what was promised, so accuracy has to come from the
     * order rows. Those aggregates are served by the V17 index
     * {@code idx_order_eta_accuracy (delivered_at, estimated_delivery_at)}.
     *
     * @param since window start; deliveries are matched on {@code delivered_at}
     * @return a fully populated summary, using zeros rather than nulls when the window
     *         contains nothing measurable
     */
    private AdminOperationsDashboardResponse.EtaAccuracySummary buildEtaAccuracy(LocalDateTime since) {
        long measured = orderRepository.countMeasurableDeliveriesSince(since);
        long onTime = orderRepository.countOnTimeDeliveriesSince(since);
        Double avgEta = etaSnapshotRepository.avgEtaMinutesSince(since);

        return AdminOperationsDashboardResponse.EtaAccuracySummary.builder()
                .snapshotsLast24h(etaSnapshotRepository.countByRecordedAtAfter(since))
                .avgEtaMinutes(avgEta != null ? avgEta : 0.0)
                .measuredDeliveriesLast24h(measured)
                .onTimeDeliveriesLast24h(onTime)
                .onTimeRatePercent(measured > 0 ? round2(onTime * 100.0 / measured) : 0.0)
                .avgLateMinutes(averageLateMinutes(since))
                .build();
    }

    /**
     * Averages how many minutes late the late deliveries in the window were. On-time
     * deliveries are excluded by the query, so this answers "when we miss, by how
     * much?" rather than diluting the figure with early arrivals.
     *
     * <p>The minute subtraction happens here instead of in JPQL, which has no portable
     * timestamp-difference function.
     *
     * @param since window start; deliveries are matched on {@code delivered_at}
     * @return average overshoot in minutes, or {@code 0.0} when nothing ran late
     */
    private Double averageLateMinutes(LocalDateTime since) {
        List<Object[]> lateRows = orderRepository.findLateDeliveryTimestampsSince(
                since, PageRequest.of(0, LATE_SAMPLE_LIMIT));
        if (lateRows.isEmpty()) {
            return 0.0;
        }
        long totalMinutes = 0L;
        for (Object[] row : lateRows) {
            LocalDateTime promised = (LocalDateTime) row[0];
            LocalDateTime actual = (LocalDateTime) row[1];
            totalMinutes += Duration.between(promised, actual).toMinutes();
        }
        return round2((double) totalMinutes / lateRows.size());
    }

    /** Trims a metric to two decimals so the dashboard renders a stable number. */
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private AdminOperationsDashboardResponse.SettlementRunSummary toRunSummary(SettlementRun run) {
        return AdminOperationsDashboardResponse.SettlementRunSummary.builder()
                .id(run.getId())
                .runType(run.getRunType())
                .status(run.getStatus().name())
                .restaurantsSettled(run.getRestaurantsSettled())
                .agentsSettled(run.getAgentsSettled())
                .totalAmount(run.getTotalAmount())
                .startedAt(run.getStartedAt() != null ? run.getStartedAt().toString() : null)
                .build();
    }
}
