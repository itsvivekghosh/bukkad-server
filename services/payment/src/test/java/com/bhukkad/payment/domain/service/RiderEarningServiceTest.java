package com.bhukkad.payment.domain.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.entity.RiderEarning;
import com.bhukkad.payment.domain.repository.RiderEarningRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RiderEarningServiceTest {

    @Mock private RiderEarningRepository earningRepository;
    @InjectMocks private RiderEarningService service;

    @Test
    void record_nullAmount_throws() {
        assertThatThrownBy(() -> service.record(1L, 2L, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void record_zeroAmount_throws() {
        assertThatThrownBy(() -> service.record(1L, 2L, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void record_negativeAmount_throws() {
        assertThatThrownBy(() -> service.record(1L, 2L, new BigDecimal("-5.00")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void record_overCap_throws() {
        assertThatThrownBy(() -> service.record(1L, 2L, new BigDecimal("10000.01")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("per-delivery limit");
    }

    @Test
    void record_exactCap_isAccepted() {
        when(earningRepository.countByAgentIdAndOrderId(1L, 2L)).thenReturn(0L);
        when(earningRepository.save(any(RiderEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        RiderEarningService.EarningResult result = service.record(1L, 2L, new BigDecimal("10000.00"));

        assertThat(result.duplicate()).isFalse();
        assertThat(result.earning().getAmount()).isEqualByComparingTo("10000.00");
    }

    @Test
    void record_existingRow_isDuplicateAndWritesNothing() {
        when(earningRepository.countByAgentIdAndOrderId(1L, 2L)).thenReturn(1L);

        RiderEarningService.EarningResult result = service.record(1L, 2L, new BigDecimal("55.00"));

        assertThat(result.duplicate()).isTrue();
        assertThat(result.earning()).isNull();
        verify(earningRepository, never()).save(any());
    }

    @Test
    void record_newEarning_savesEarnedRow() {
        when(earningRepository.countByAgentIdAndOrderId(1L, 2L)).thenReturn(0L);
        when(earningRepository.save(any(RiderEarning.class))).thenAnswer(inv -> inv.getArgument(0));

        RiderEarningService.EarningResult result = service.record(1L, 2L, new BigDecimal("55.00"));

        ArgumentCaptor<RiderEarning> captor = ArgumentCaptor.forClass(RiderEarning.class);
        verify(earningRepository).save(captor.capture());
        assertThat(captor.getValue().getAgentId()).isEqualTo(1L);
        assertThat(captor.getValue().getOrderId()).isEqualTo(2L);
        assertThat(captor.getValue().getStatus()).isEqualTo("EARNED");
        assertThat(result.earning()).isSameAs(captor.getValue());
    }

    @Test
    void listByAgent_delegates() {
        RiderEarning earning = new RiderEarning();
        when(earningRepository.findByAgentId(7L)).thenReturn(List.of(earning));

        assertThat(service.listByAgent(7L)).containsExactly(earning);
    }

    @Test
    void markPaid_guardedTransitionSucceeds() {
        when(earningRepository.markPaidIfEarned(3L)).thenReturn(1);

        service.markPaid(3L);

        verify(earningRepository).markPaidIfEarned(3L);
    }

    @Test
    void markPaid_nonEarnedRow_throws() {
        when(earningRepository.markPaidIfEarned(3L)).thenReturn(0);

        assertThatThrownBy(() -> service.markPaid(3L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("EARNED");
    }
}
