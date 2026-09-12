package com.bhukkad.support.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.support.domain.entity.Dispute;
import com.bhukkad.support.domain.repository.DisputeRepository;
import com.bhukkad.support.dto.OrderDetailDto;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
import com.bhukkad.support.infrastructure.client.OrderServiceClient;
import com.bhukkad.support.infrastructure.client.WalletCreditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Late-delivery auto-resolution branch (partial refund, 10% capped at 100)
 * and the remaining refund-amount resolution paths of
 * {@link DisputeResolutionServiceImpl}: non-refundable totals, missing orders
 * on full refunds, computed and zero partials, and no-refund resolutions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeLateDeliveryAutoResolutionTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletCreditClient walletClient;
    @Mock private OrderServiceClient orderClient;
    private DisputeResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DisputeResolutionServiceImpl(disputeRepository, walletClient, orderClient);
        ReflectionTestUtils.setField(service, "lateThresholdMinutes", 30L);
    }

    private Dispute lateOpen(Dispute.DisputeType type) {
        Dispute d = new Dispute();
        d.setId(9L);
        d.setCustomerId(7L);
        d.setOrderId(42L);
        d.setStatus(Dispute.DisputeStatus.OPEN);
        d.setType(type);
        d.setCustomerEvidence("arrived 45 min late");
        d.setCreatedAt(LocalDateTime.now().minusHours(2));
        return d;
    }

    private OrderDetailDto delivered(BigDecimal total, long lateMinutes) {
        OrderDetailDto o = new OrderDetailDto();
        o.setCustomerId(7L);
        o.setStatus("DELIVERED");
        o.setTotalAmount(total);
        o.setEstimatedDeliveryAt(LocalDateTime.of(2026, 1, 2, 10, 0));
        o.setDeliveredAt(o.getEstimatedDeliveryAt().plusMinutes(lateMinutes));
        return o;
    }

    @Test
    void triggerAutoResolution_lateDeliveryRefundsTenPercentCapped() {
        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(
                List.of(Dispute.DisputeStatus.OPEN, Dispute.DisputeStatus.UNDER_REVIEW)))
                .thenReturn(List.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(delivered(new BigDecimal("1500.00"), 45));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isEqualTo(1);
        // 10% of 1500 = 150 but capped at 100.
        verify(walletClient).credit(eq(7L), eq(100.0), eq("42"), isNull(), eq("dispute-refund"));
        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Dispute.DisputeStatus.AUTO_RESOLVED);
        assertThat(saved.getValue().getResolution()).isEqualTo(Dispute.DisputeResolution.PARTIAL_REFUND);
        assertThat(saved.getValue().getRefundAmount()).isEqualTo(100.0);
        assertThat(saved.getValue().getResolvedById()).isNull();
    }

    @Test
    void triggerAutoResolution_lateDeliveryWithZeroTotal_skipsCreditButStillResolves() {
        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(delivered(BigDecimal.ZERO, 45));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isEqualTo(1);
        verify(walletClient, never()).credit(anyLong(), anyDouble(), anyString(), any(), anyString());
    }

    @Test
    void triggerAutoResolution_lateButUnderThreshold_goesToManualReview() {
        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(delivered(new BigDecimal("500.00"), 20));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isZero();
        ArgumentCaptor<Dispute> saved = ArgumentCaptor.forClass(Dispute.class);
        verify(disputeRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(Dispute.DisputeStatus.UNDER_REVIEW);
    }

    @Test
    void manualResolve_fullRefund_orderTotalNotRefundable_isRejected() {
        when(disputeRepository.findById(9L)).thenReturn(Optional.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(delivered(BigDecimal.ZERO, 45));

        DisputeResolveRequest req = new DisputeResolveRequest();
        req.setResolution("FULL_REFUND");
        req.setRefundAmount(50.0);

        assertThatThrownBy(() -> service.manualResolve(1L, 9L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Order total is not refundable");
    }

    @Test
    void manualResolve_fullRefundWithoutAmount_orderUnreachable_isRejected() {
        when(disputeRepository.findById(9L)).thenReturn(Optional.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(null);

        DisputeResolveRequest req = new DisputeResolveRequest();
        req.setResolution("FULL_REFUND");
        req.setRefundAmount(null);

        assertThatThrownBy(() -> service.manualResolve(1L, 9L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Full refund requires a positive amount");
    }

    @Test
    void manualResolve_partialWithoutAmount_computesLateDeliveryRefund() {
        when(disputeRepository.findById(9L)).thenReturn(Optional.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(delivered(new BigDecimal("400.00"), 45));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        DisputeResolveRequest req = new DisputeResolveRequest();
        req.setResolution("PARTIAL_REFUND");
        req.setRefundAmount(null);

        service.manualResolve(1L, 9L, req);

        // 10% of 400 = 40 (under the 100 cap).
        verify(walletClient).credit(eq(7L), eq(40.0), eq("42"), isNull(), eq("dispute-refund"));
    }

    @Test
    void manualResolve_partialWithoutAmount_notLate_resolvesWithoutRefund() {
        when(disputeRepository.findById(9L)).thenReturn(Optional.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(orderClient.getOrderDetails(42L)).thenReturn(delivered(new BigDecimal("400.00"), 10));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        DisputeResolveRequest req = new DisputeResolveRequest();
        req.setResolution("PARTIAL_REFUND");
        req.setRefundAmount(null);

        var response = service.manualResolve(1L, 9L, req);

        verify(walletClient, never()).credit(anyLong(), anyDouble(), anyString(), any(), anyString());
        assertThat(response.getRefundAmount()).isNull();
    }

    @Test
    void manualResolve_noRefundResolution_setsNoRefund() {
        when(disputeRepository.findById(9L)).thenReturn(Optional.of(lateOpen(Dispute.DisputeType.LATE_DELIVERY)));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        DisputeResolveRequest req = new DisputeResolveRequest();
        req.setResolution("NO_REFUND");
        req.setNotes("Evidence insufficient");

        service.manualResolve(1L, 9L, req);

        verify(walletClient, never()).credit(anyLong(), anyDouble(), anyString(), any(), anyString());
    }
}
