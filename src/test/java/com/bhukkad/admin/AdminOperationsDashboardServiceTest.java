package com.bhukkad.admin;

import com.bhukkad.dto.response.AdminOperationsDashboardResponse;
import com.bhukkad.repository.OrderEtaSnapshotRepository;
import com.bhukkad.repository.OrderRepository;
import com.bhukkad.repository.RestaurantSettlementRepository;
import com.bhukkad.repository.RiderDeliveryBatchRepository;
import com.bhukkad.repository.RiderEarningRepository;
import com.bhukkad.repository.SettlementRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ETA accuracy panel of the operations dashboard (V17).
 *
 * <p>Scope note: the repository aggregates themselves are JPQL strings and this project
 * has no H2, no Testcontainers and no {@code @DataJpaTest}, so the queries cannot be
 * executed here. These tests pin the arithmetic and null handling that the service layer
 * owns — on-time rate, average lateness across late deliveries only, the sample cap, and
 * behaviour on an empty window.
 */
@ExtendWith(MockitoExtension.class)
class AdminOperationsDashboardServiceTest {

    @Mock
    private RestaurantSettlementRepository settlementRepository;
    @Mock
    private RiderEarningRepository riderEarningRepository;
    @Mock
    private SettlementRunRepository settlementRunRepository;
    @Mock
    private RiderDeliveryBatchRepository batchRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderEtaSnapshotRepository etaSnapshotRepository;

    private AdminOperationsDashboardService service;

    @BeforeEach
    void setUp() {
        service = new AdminOperationsDashboardService(
                settlementRepository,
                riderEarningRepository,
                settlementRunRepository,
                batchRepository,
                orderRepository,
                etaSnapshotRepository);
    }

    /** Builds a promised/actual pair that is {@code lateByMinutes} past the promise. */
    private Object[] lateRow(int lateByMinutes) {
        LocalDateTime promised = LocalDateTime.of(2026, 8, 15, 12, 0);
        return new Object[]{promised, promised.plusMinutes(lateByMinutes)};
    }

    @Test
    void etaAccuracy_reportsOnTimeRateAndAverageLateness() {
        when(etaSnapshotRepository.countByRecordedAtAfter(any())).thenReturn(42L);
        when(etaSnapshotRepository.avgEtaMinutesSince(any())).thenReturn(31.5);
        when(orderRepository.countMeasurableDeliveriesSince(any())).thenReturn(10L);
        when(orderRepository.countOnTimeDeliveriesSince(any())).thenReturn(7L);
        when(orderRepository.findLateDeliveryTimestampsSince(any(), any()))
                .thenReturn(List.of(lateRow(5), lateRow(10)));

        AdminOperationsDashboardResponse.EtaAccuracySummary eta = service.getDashboard().getEtaAccuracy();

        assertThat(eta.getSnapshotsLast24h()).isEqualTo(42L);
        assertThat(eta.getAvgEtaMinutes()).isEqualTo(31.5);
        assertThat(eta.getMeasuredDeliveriesLast24h()).isEqualTo(10L);
        assertThat(eta.getOnTimeDeliveriesLast24h()).isEqualTo(7L);
        assertThat(eta.getOnTimeRatePercent()).isEqualTo(70.0);
        assertThat(eta.getAvgLateMinutes()).isEqualTo(7.5);
    }

    @Test
    void etaAccuracy_emptyWindowYieldsZerosInsteadOfNullsOrDivideByZero() {
        when(etaSnapshotRepository.countByRecordedAtAfter(any())).thenReturn(0L);
        when(etaSnapshotRepository.avgEtaMinutesSince(any())).thenReturn(null);
        when(orderRepository.countMeasurableDeliveriesSince(any())).thenReturn(0L);
        when(orderRepository.countOnTimeDeliveriesSince(any())).thenReturn(0L);
        when(orderRepository.findLateDeliveryTimestampsSince(any(), any())).thenReturn(List.of());

        AdminOperationsDashboardResponse.EtaAccuracySummary eta = service.getDashboard().getEtaAccuracy();

        assertThat(eta.getAvgEtaMinutes()).isEqualTo(0.0);
        assertThat(eta.getMeasuredDeliveriesLast24h()).isZero();
        assertThat(eta.getOnTimeRatePercent()).isEqualTo(0.0);
        assertThat(eta.getAvgLateMinutes()).isEqualTo(0.0);
    }

    @Test
    void etaAccuracy_roundsRateAndLatenessToTwoDecimals() {
        when(etaSnapshotRepository.countByRecordedAtAfter(any())).thenReturn(3L);
        when(etaSnapshotRepository.avgEtaMinutesSince(any())).thenReturn(28.0);
        when(orderRepository.countMeasurableDeliveriesSince(any())).thenReturn(3L);
        when(orderRepository.countOnTimeDeliveriesSince(any())).thenReturn(1L);
        when(orderRepository.findLateDeliveryTimestampsSince(any(), any()))
                .thenReturn(List.of(lateRow(1), lateRow(2), lateRow(2)));

        AdminOperationsDashboardResponse.EtaAccuracySummary eta = service.getDashboard().getEtaAccuracy();

        assertThat(eta.getOnTimeRatePercent()).isEqualTo(33.33);
        assertThat(eta.getAvgLateMinutes()).isEqualTo(1.67);
    }

    @Test
    void etaAccuracy_boundsTheLateDeliverySample() {
        when(etaSnapshotRepository.countByRecordedAtAfter(any())).thenReturn(1L);
        when(etaSnapshotRepository.avgEtaMinutesSince(any())).thenReturn(20.0);
        when(orderRepository.countMeasurableDeliveriesSince(any())).thenReturn(1L);
        when(orderRepository.countOnTimeDeliveriesSince(any())).thenReturn(1L);
        when(orderRepository.findLateDeliveryTimestampsSince(any(), any())).thenReturn(List.of());

        service.getDashboard();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(orderRepository).findLateDeliveryTimestampsSince(any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(500);
    }
}
