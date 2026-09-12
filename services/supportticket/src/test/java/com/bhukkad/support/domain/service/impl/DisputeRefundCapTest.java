package com.bhukkad.support.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.support.infrastructure.client.OrderServiceClient;
import com.bhukkad.support.infrastructure.client.WalletCreditClient;
import com.bhukkad.support.domain.entity.Dispute;
import com.bhukkad.support.domain.repository.DisputeRepository;
import com.bhukkad.support.dto.OrderDetailDto;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Refund caps (audit H-3): no resolution path — manual FULL_REFUND with an
 * admin-supplied amount included — may credit more than the order's paid
 * total, and an unreachable order never invents money.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeRefundCapTest {

    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletCreditClient walletClient;
    @Mock private OrderServiceClient orderClient;
    private DisputeResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DisputeResolutionServiceImpl(disputeRepository, walletClient, orderClient);
        ReflectionTestUtils.setField(service, "lateThresholdMinutes", 30L);
    }

    private Dispute open() {
        Dispute d = new Dispute();
        d.setId(5L);
        d.setCustomerId(7L);
        d.setOrderId(42L);
        d.setStatus(Dispute.DisputeStatus.OPEN);
        d.setType(Dispute.DisputeType.LATE_DELIVERY);
        d.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return d;
    }

    private OrderDetailDto order(String total) {
        OrderDetailDto o = new OrderDetailDto();
        o.setTotalAmount(new BigDecimal(total));
        return o;
    }

    private DisputeResolveRequest full(Double amount) {
        DisputeResolveRequest req = new DisputeResolveRequest();
        req.setResolution("FULL_REFUND");
        req.setRefundAmount(amount);
        return req;
    }

    @Test
    void manualFullRefund_clampsAdminAmountToOrderTotal() {
        when(disputeRepository.findById(5L)).thenReturn(Optional.of(open()));
        when(orderClient.getOrderDetails(42L)).thenReturn(order("250.00"));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        service.manualResolve(1L, 5L, full(999_999.99));

        ArgumentCaptor<Double> credited = ArgumentCaptor.forClass(Double.class);
        verify(walletClient).credit(eq(7L), credited.capture(), eq("42"), isNull(), eq("dispute-refund"));
        assertThat(credited.getValue()).isEqualTo(250.00);
    }

    @Test
    void manualFullRefund_orderUnreachable_isRejectedNotInvented() {
        when(disputeRepository.findById(5L)).thenReturn(Optional.of(open()));
        when(orderClient.getOrderDetails(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.manualResolve(1L, 5L, full(50.0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be validated");
        verify(walletClient, never()).credit(anyLong(), anyDouble(), anyString(), any(), anyString());
    }

    @Test
    void manualFullRefund_withoutAmount_usesOrderTotal() {
        when(disputeRepository.findById(5L)).thenReturn(Optional.of(open()));
        when(orderClient.getOrderDetails(42L)).thenReturn(order("120.50"));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        service.manualResolve(1L, 5L, full(null));

        ArgumentCaptor<Double> credited = ArgumentCaptor.forClass(Double.class);
        verify(walletClient).credit(eq(7L), credited.capture(), eq("42"), isNull(), eq("dispute-refund"));
        assertThat(credited.getValue()).isEqualTo(120.50);
    }

    @Test
    void alreadyClosed_rejected() {
        Dispute closed = open();
        closed.setStatus(Dispute.DisputeStatus.CLOSED);
        when(disputeRepository.findById(5L)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.manualResolve(1L, 5L, full(10.0)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already closed");
    }
}
