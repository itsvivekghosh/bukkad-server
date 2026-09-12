package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.entity.CommissionTier;
import com.bhukkad.payment.domain.repository.CommissionTierRepository;
import com.bhukkad.payment.domain.entity.RestaurantSettlement;
import com.bhukkad.payment.domain.repository.RestaurantSettlementRepository;
import com.bhukkad.payment.domain.entity.SettlementRun;
import com.bhukkad.payment.domain.repository.SettlementRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock private SettlementRunRepository runRepository;
    @Mock private RestaurantSettlementRepository settlementRepository;
    @Mock private CommissionTierService commissionTierService;
    @InjectMocks private SettlementService service;

    private void stubSaves() {
        when(runRepository.save(any(SettlementRun.class)))
                .thenAnswer(inv -> {
                    SettlementRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(1L);
                    return r;
                });
        when(settlementRepository.save(any(RestaurantSettlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void run_validInputs_computesTierCommissionAndCompletes() {
        stubSaves();
        // H-4: 20% now comes from the tier table (orderCount 10), not a
        // hardcoded rate.
        when(commissionTierService.commissionFor(10)).thenReturn(new BigDecimal("20.00"));

        SettlementService.SettlementResult result = service.run(
                LocalDate.of(2025, 1, 15), 42L, 10, new BigDecimal("1000.00"));

        assertThat(result.run().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.run().getRunDate()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(result.run().getTotalAmount()).isEqualByComparingTo("800.00");
        assertThat(result.settlement().getCommission()).isEqualByComparingTo("200.00");
        assertThat(result.settlement().getNetAmount()).isEqualByComparingTo("800.00");
    }

    @Test
    void run_usesTierPctMappedFromOrderCount() {
        // 5 orders → the (0..10] tier pays 15%; 150 orders → the open-ended
        // 100+ tier pays 10%; net = gross - gross*pct/100.
        CommissionTierRepository tierRepository = mock(CommissionTierRepository.class);
        when(tierRepository.findByActiveTrueOrderByMinOrderCountAsc()).thenReturn(List.of(
                tier(0, 10, "15.00"), tier(100, null, "10.00")));
        stubSaves();

        SettlementService tiered = new SettlementService(runRepository, settlementRepository,
                new CommissionTierService(tierRepository));

        SettlementService.SettlementResult five = tiered.run(
                LocalDate.now(), 7L, 5, new BigDecimal("1000.00"));
        assertThat(five.settlement().getCommission()).isEqualByComparingTo("150.00");
        assertThat(five.settlement().getNetAmount()).isEqualByComparingTo("850.00");

        SettlementService.SettlementResult bulk = tiered.run(
                LocalDate.now(), 7L, 150, new BigDecimal("1200.00"));
        assertThat(bulk.settlement().getCommission()).isEqualByComparingTo("120.00");
        assertThat(bulk.settlement().getNetAmount()).isEqualByComparingTo("1080.00");

        verify(settlementRepository, org.mockito.Mockito.times(2)).save(any(RestaurantSettlement.class));
    }

    @Test
    void run_smallTicketCommission_roundsHalfUpToTwoPlaces() {
        stubSaves();
        when(commissionTierService.commissionFor(20)).thenReturn(new BigDecimal("12.50"));

        SettlementService.SettlementResult result = service.run(
                LocalDate.now(), 42L, 20, new BigDecimal("999.99"));

        // 999.99 * 12.50 / 100 = 124.99875 → HALF_UP → 125.00; net 874.99.
        assertThat(result.settlement().getCommission()).isEqualByComparingTo("125.00");
        assertThat(result.settlement().getNetAmount()).isEqualByComparingTo("874.99");
    }

    @Test
    void run_noTiersConfigured_chargesZeroCommission() {
        stubSaves();
        // commissionFor falls back to ZERO below the repository when no tier
        // matches; settlement then charges nothing but still completes.
        when(commissionTierService.commissionFor(0)).thenReturn(BigDecimal.ZERO);

        SettlementService.SettlementResult result = service.run(
                LocalDate.now(), 42L, 0, new BigDecimal("500.00"));

        assertThat(result.settlement().getCommission()).isEqualByComparingTo("0.00");
        assertThat(result.settlement().getNetAmount()).isEqualByComparingTo("500.00");
    }

    @Test
    void run_negativeOrderCount_throws() {
        assertThatThrownBy(() -> service.run(LocalDate.now(), 42L, -1, new BigDecimal("100.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void run_negativeGross_throws() {
        assertThatThrownBy(() -> service.run(LocalDate.now(), 42L, 5, new BigDecimal("-10.00")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid");
    }

    private CommissionTier tier(int min, Integer max, String pct) {
        CommissionTier t = new CommissionTier();
        t.setMinOrderCount(min);
        t.setMaxOrderCount(max);
        t.setCommissionPct(new BigDecimal(pct));
        t.setActive(true);
        return t;
    }
}
