package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.RestaurantSettlement;
import com.bhukkad.payment.domain.RestaurantSettlementRepository;
import com.bhukkad.payment.domain.SettlementRun;
import com.bhukkad.payment.domain.SettlementRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettlementServiceTest {

    @Mock private SettlementRunRepository runRepository;
    @Mock private RestaurantSettlementRepository settlementRepository;
    @InjectMocks private SettlementService service;

    @Test
    void run_validInputs_computesCommissionAndCompletes() {
        SettlementRun savedRun = new SettlementRun();
        savedRun.setId(1L);
        savedRun.setStatus("RUNNING");
        when(runRepository.save(any(SettlementRun.class)))
                .thenAnswer(inv -> {
                    SettlementRun r = inv.getArgument(0);
                    if (r.getId() == null) r.setId(1L);
                    return r;
                });
        when(settlementRepository.save(any(RestaurantSettlement.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SettlementService.SettlementResult result = service.run(
                LocalDate.of(2025, 1, 15), 42L, 10, new BigDecimal("1000.00"));

        assertThat(result.run().getStatus()).isEqualTo("COMPLETED");
        assertThat(result.run().getRunDate()).isEqualTo(LocalDate.of(2025, 1, 15));
        assertThat(result.run().getTotalAmount()).isEqualByComparingTo("800.00");
        assertThat(result.settlement().getNetAmount()).isEqualByComparingTo("800.00");
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
}
