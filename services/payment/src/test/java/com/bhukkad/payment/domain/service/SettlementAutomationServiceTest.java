package com.bhukkad.payment.domain.service;

import com.bhukkad.payment.domain.repository.RestaurantSettlementRepository;
import com.bhukkad.payment.domain.entity.SettlementRun;
import com.bhukkad.payment.domain.repository.SettlementRunRepository;
import com.bhukkad.payment.domain.event.PaymentEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.bhukkad.payment.config.SettlementAutomationProperties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SettlementAutomationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 5);

    @Mock private SettlementRunRepository runRepository;
    @Mock private RestaurantSettlementRepository settlementRepository;
    @Mock private PaymentEventPublisher eventPublisher;

    private SettlementAutomationService service(SettlementAutomationProperties props) {
        return new SettlementAutomationService(runRepository, settlementRepository, props, eventPublisher);
    }

    private SettlementAutomationService service() {
        return service(new SettlementAutomationProperties());
    }

    @Test
    void settleFor_runDateAlreadySettled_skipsWithoutTouches() {
        when(runRepository.existsByRunDate(DATE)).thenReturn(true);

        Optional<SettlementAutomationService.AutomationResult> result = service().settleFor(DATE);

        assertThat(result).isEmpty();
        verifyNoInteractions(settlementRepository, eventPublisher);
        verify(runRepository, never()).save(any());
    }

    @Test
    void settleFor_pendingsAboveThreshold_flipsSettlesCompletesAndPublishes() {
        when(runRepository.existsByRunDate(DATE)).thenReturn(false);
        when(settlementRepository.findDistinctRestaurantIdsByStatus("PENDING"))
                .thenReturn(List.of(7L, 9L));
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(7L, "PENDING"))
                .thenReturn(new BigDecimal("500.00"));
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(9L, "PENDING"))
                .thenReturn(new BigDecimal("300.00"));
        when(settlementRepository.atomicSettleByRestaurant(7L, "PENDING", "SETTLED")).thenReturn(3);
        when(settlementRepository.atomicSettleByRestaurant(9L, "PENDING", "SETTLED")).thenReturn(2);
        when(runRepository.save(any(SettlementRun.class))).thenAnswer(inv -> {
            SettlementRun r = inv.getArgument(0);
            r.setId(42L);
            return r;
        });

        var result = service().settleFor(DATE).orElseThrow();

        assertThat(result.restaurantsSettled()).isEqualTo(2);
        assertThat(result.rowsSettled()).isEqualTo(5);
        assertThat(result.totalNet()).isEqualByComparingTo("800.00");
        assertThat(result.run().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.run().getRunDate()).isEqualTo(DATE);

        ArgumentCaptor<BigDecimal> totalCap = ArgumentCaptor.forClass(BigDecimal.class);
        verify(eventPublisher).settlementRunCompleted(eq(42L), eq(DATE), eq(2), eq(5), totalCap.capture());
        assertThat(totalCap.getValue()).isEqualByComparingTo("800.00");
    }

    @Test
    void settleFor_belowMinimumPending_notSettled() {
        when(runRepository.existsByRunDate(DATE)).thenReturn(false);
        when(settlementRepository.findDistinctRestaurantIdsByStatus("PENDING"))
                .thenReturn(List.of(5L));
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(5L, "PENDING"))
                .thenReturn(new BigDecimal("99.99"));
        when(runRepository.save(any(SettlementRun.class))).thenAnswer(inv -> {
            SettlementRun r = inv.getArgument(0);
            r.setId(43L);
            return r;
        });

        var result = service().settleFor(DATE).orElseThrow();

        assertThat(result.restaurantsSettled()).isZero();
        assertThat(result.totalNet()).isEqualByComparingTo("0");
        verify(settlementRepository, never())
                .atomicSettleByRestaurant(anyLong(), anyString(), anyString());
        // The no-op day is still pinned (one run per date) and announced.
        verify(eventPublisher).settlementRunCompleted(eq(43L), eq(DATE), eq(0), eq(0), any());
    }

    @Test
    void settleFor_concurrentInstanceFlippedFirst_notDoubleCounted() {
        when(runRepository.existsByRunDate(DATE)).thenReturn(false);
        when(settlementRepository.findDistinctRestaurantIdsByStatus("PENDING"))
                .thenReturn(List.of(11L));
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(11L, "PENDING"))
                .thenReturn(new BigDecimal("1200.00"));
        // A rolling-release overlap already settled every row: the guarded atomic
        // UPDATE matches nothing, so this run must contribute zero.
        when(settlementRepository.atomicSettleByRestaurant(11L, "PENDING", "SETTLED")).thenReturn(0);
        when(runRepository.save(any(SettlementRun.class))).thenAnswer(inv -> {
            SettlementRun r = inv.getArgument(0);
            r.setId(44L);
            return r;
        });

        var result = service().settleFor(DATE).orElseThrow();

        assertThat(result.restaurantsSettled()).isZero();
        assertThat(result.totalNet()).isEqualByComparingTo("0");
    }

    @Test
    void settleFor_batchLimit_truncatesScan() {
        SettlementAutomationProperties props = new SettlementAutomationProperties();
        props.setBatchLimit(2);
        List<Long> ids = List.of(1L, 2L, 3L, 4L);
        when(runRepository.existsByRunDate(DATE)).thenReturn(false);
        when(settlementRepository.findDistinctRestaurantIdsByStatus("PENDING")).thenReturn(ids);
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(anyLong(), eq("PENDING")))
                .thenReturn(new BigDecimal("200.00"));
        when(settlementRepository.atomicSettleByRestaurant(anyLong(), eq("PENDING"), eq("SETTLED")))
                .thenReturn(1);
        when(runRepository.save(any(SettlementRun.class))).thenReturn(new SettlementRun());

        var result = service(props).settleFor(DATE).orElseThrow();

        assertThat(result.restaurantsSettled()).isEqualTo(2);
        verify(settlementRepository, never())
                .atomicSettleByRestaurant(eq(3L), anyString(), anyString());
        verify(settlementRepository, never())
                .atomicSettleByRestaurant(eq(4L), anyString(), anyString());
    }

    @Test
    void settleFor_minPendingThresholdRespectsCustomValue() {
        SettlementAutomationProperties props = new SettlementAutomationProperties();
        props.setMinPendingAmount(new BigDecimal("1000.00"));
        when(runRepository.existsByRunDate(DATE)).thenReturn(false);
        when(settlementRepository.findDistinctRestaurantIdsByStatus("PENDING"))
                .thenReturn(List.of(3L));
        when(settlementRepository.sumNetAmountByRestaurantAndStatus(3L, "PENDING"))
                .thenReturn(new BigDecimal("999.99"));
        when(runRepository.save(any(SettlementRun.class))).thenReturn(new SettlementRun());

        var result = service(props).settleFor(DATE).orElseThrow();

        assertThat(result.restaurantsSettled()).isZero();
        verify(settlementRepository, never())
                .atomicSettleByRestaurant(anyLong(), anyString(), anyString());
    }

    @Test
    void properties_defaultsMirrorMonolithBlock() {
        SettlementAutomationProperties props = new SettlementAutomationProperties();
        assertThat(props.isAutoEnabled()).isFalse();
        assertThat(props.getAutoSettleCron()).isEqualTo("0 0 2 * * *");
        assertThat(props.getMinPendingAmount()).isEqualByComparingTo("100.00");
        assertThat(props.getBatchLimit()).isEqualTo(500);
    }
}
