package com.bhukkad.payment.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.payment.domain.Dispute;
import com.bhukkad.payment.domain.DisputeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeServiceTest {

    @Mock private DisputeRepository disputeRepository;
    @InjectMocks private DisputeService service;

    private Dispute openDispute() {
        Dispute dispute = new Dispute();
        dispute.setId(7L);
        dispute.setPaymentId(1L);
        dispute.setCustomerId(2L);
        dispute.setOrderId(3L);
        dispute.setReason("wrong item");
        dispute.setAmount(new BigDecimal("150.00"));
        dispute.setStatus(Dispute.STATUS_OPEN);
        return dispute;
    }

    @Test
    void raise_validAmount_createsOpenDispute() {
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        Dispute dispute = service.raise(1L, 2L, 3L, "wrong item", new BigDecimal("150.00"));

        assertThat(dispute.getPaymentId()).isEqualTo(1L);
        assertThat(dispute.getCustomerId()).isEqualTo(2L);
        assertThat(dispute.getOrderId()).isEqualTo(3L);
        assertThat(dispute.getReason()).isEqualTo("wrong item");
        assertThat(dispute.getAmount()).isEqualByComparingTo("150.00");
        assertThat(dispute.getStatus()).isEqualTo(Dispute.STATUS_OPEN);
    }

    @Test
    void raise_nonPositiveAmount_throws() {
        assertThatThrownBy(() -> service.raise(1L, 2L, 3L, "x", BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void resolve_refundTrue_marksRefunded() {
        Dispute open = openDispute();
        when(disputeRepository.findById(7L)).thenReturn(Optional.of(open));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        Dispute resolved = service.resolve(7L, true);

        assertThat(resolved.getStatus()).isEqualTo(Dispute.STATUS_REFUNDED);
        assertThat(resolved.getResolution()).isEqualTo("Refunded");
    }

    @Test
    void resolve_refundFalse_marksRejected() {
        Dispute open = openDispute();
        when(disputeRepository.findById(7L)).thenReturn(Optional.of(open));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        Dispute resolved = service.resolve(7L, false);

        assertThat(resolved.getStatus()).isEqualTo(Dispute.STATUS_REJECTED);
        assertThat(resolved.getResolution()).isEqualTo("Rejected after review");
    }

    @Test
    void resolve_alreadyResolved_throws() {
        Dispute resolved = openDispute();
        resolved.setStatus(Dispute.STATUS_REFUNDED);
        when(disputeRepository.findById(7L)).thenReturn(Optional.of(resolved));

        assertThatThrownBy(() -> service.resolve(7L, true))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already resolved");
    }

    @Test
    void resolve_unknownDispute_throws() {
        when(disputeRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve(99L, true))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void byCustomer_returnsCustomerDisputes() {
        when(disputeRepository.findByCustomerId(2L)).thenReturn(List.of(openDispute()));

        assertThat(service.byCustomer(2L)).hasSize(1);
    }
}
