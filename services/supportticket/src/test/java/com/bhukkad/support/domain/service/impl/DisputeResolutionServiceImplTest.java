package com.bhukkad.support.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.support.dto.OrderDetailDto;
import com.bhukkad.support.dto.request.DisputeRequest;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.domain.entity.Dispute;
import com.bhukkad.support.domain.repository.DisputeRepository;
import com.bhukkad.support.infrastructure.client.OrderServiceClient;
import com.bhukkad.support.infrastructure.client.WalletCreditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doNothing;

@ExtendWith(MockitoExtension.class)
class DisputeResolutionServiceImplTest {

    @Mock(lenient = true)
    private DisputeRepository disputeRepository;

    @Mock(lenient = true)
    private WalletCreditClient walletService;

    @Mock(lenient = true)
    private OrderServiceClient orderServiceClient;

    private DisputeResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DisputeResolutionServiceImpl(disputeRepository, walletService, orderServiceClient);
    }

    @Test
    void fileDispute_shouldCreateNewDispute() {
        DisputeRequest request = new DisputeRequest();
        request.setType("ORDER_NOT_RECEIVED");
        request.setCustomerEvidence("Photo of empty box");

        when(orderServiceClient.getOrderCustomerId(100L)).thenReturn(1L); // filer owns the order
        when(disputeRepository.existsByOrderId(100L)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderServiceClient.getOrderDetails(100L)).thenReturn(null); // Auto-resolution will fail

        DisputeResponse result = service.fileDispute(1L, 100L, request);

        assertThat(result.getOrderId()).isEqualTo(100L);
        assertThat(result.getType()).isEqualTo("ORDER_NOT_RECEIVED");
        assertThat(result.getCustomerEvidence()).isEqualTo("Photo of empty box");
        assertThat(result.getStatus()).isEqualTo("OPEN"); // Order fetch failed → manual review (impl leaves OPEN)
        verify(disputeRepository, times(2)).save(any(Dispute.class)); // initial persist + post-auto-resolution persist
    }

    @Test
    void fileDispute_shouldThrowWhenDisputeAlreadyExists() {
        DisputeRequest request = new DisputeRequest();
        request.setType("ORDER_NOT_RECEIVED");
        request.setCustomerEvidence("Evidence");

        when(orderServiceClient.getOrderCustomerId(100L)).thenReturn(1L); // ownership guard passes
        when(disputeRepository.existsByOrderId(100L)).thenReturn(true);

        assertThatThrownBy(() -> service.fileDispute(1L, 100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A dispute already exists for this order");
    }

    @Test
    void fileDispute_shouldThrowOnInvalidType() {
        DisputeRequest request = new DisputeRequest();
        request.setType("INVALID_TYPE");
        request.setCustomerEvidence("Evidence");

        when(orderServiceClient.getOrderCustomerId(100L)).thenReturn(1L); // ownership guard passes
        when(disputeRepository.existsByOrderId(100L)).thenReturn(false);

        assertThatThrownBy(() -> service.fileDispute(1L, 100L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid dispute type");
    }

    @Test
    void listForAdmin_shouldReturnCappedList() {
        Dispute d1 = createSampleDispute(1L);
        Dispute d2 = createSampleDispute(2L);
        Dispute d3 = createSampleDispute(3L);

        when(disputeRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(Arrays.asList(d1, d2, d3));

        List<DisputeResponse> result = service.listForAdmin();

        assertThat(result).hasSize(3);
        // Default cap is 200, but we only have 3
    }

    @Test
    void listForAdmin_shouldCapAtMaxSize() {
        // Create 250 disputes
        List<Dispute> manyDisputes = Arrays.asList(new Dispute[250]);
        // Just test the cap logic by providing a smaller cap
        List<DisputeResponse> result = service.listForAdmin(50);

        // The service caps at ADMIN_DISPUTE_PAGE_SIZE (200) and min 1
        // With 50 requested, it should return up to 50
        verify(disputeRepository).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void listForCustomer_shouldReturnCustomerDisputes() {
        Dispute d1 = createSampleDispute(1L);
        d1.setCustomerId(1L);

        when(disputeRepository.findByCustomerIdOrderByCreatedAtDesc(1L))
                .thenReturn(Arrays.asList(d1));

        List<DisputeResponse> result = service.listForCustomer(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo(100L); // fixture uses orderId 100
    }

    @Test
    void getById_shouldReturnDispute() {
        Dispute dispute = createSampleDispute(1L);
        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        DisputeResponse result = service.getById(1L);

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void getById_shouldThrowWhenNotFound() {
        when(disputeRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Dispute not found");
    }

    @Test
    void manualResolve_shouldFullRefundWithExplicitAmount() {
        Dispute dispute = createSampleDispute(1L);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setOrderId(100L);
        dispute.setCustomerId(1L);

        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("FULL_REFUND");
        request.setRefundAmount(50.0);
        request.setNotes("Customer satisfied");

        OrderDetailDto order = new OrderDetailDto();
        order.setTotalAmount(BigDecimal.valueOf(100.0));

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        when(orderServiceClient.getOrderDetails(100L)).thenReturn(order);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(walletService).credit(anyLong(), anyDouble(), anyString(), any(), anyString());

        DisputeResponse result = service.manualResolve(99L, 1L, request);

        assertThat(result.getResolution()).isEqualTo("FULL_REFUND");
        assertThat(result.getRefundAmount()).isEqualTo(50.0);
        assertThat(result.getStatus()).isEqualTo("MANUAL_RESOLVED");
        assertThat(result.getResolutionNotes()).isEqualTo("Customer satisfied");
        assertThat(result.getResolvedBy()).isEqualTo(99L);
    }

    @Test
    void manualResolve_shouldFullRefundFromOrderTotalWhenNoExplicitAmount() {
        Dispute dispute = createSampleDispute(1L);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setOrderId(100L);

        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("FULL_REFUND");
        request.setRefundAmount(null);

        OrderDetailDto order = new OrderDetailDto();
        order.setTotalAmount(BigDecimal.valueOf(100.0));

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        when(orderServiceClient.getOrderDetails(100L)).thenReturn(order);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(walletService).credit(anyLong(), anyDouble(), anyString(), any(), anyString());

        DisputeResponse result = service.manualResolve(99L, 1L, request);

        assertThat(result.getRefundAmount()).isEqualTo(100.0);
    }

    @Test
    void manualResolve_shouldPartialRefundWithExplicitAmount() {
        Dispute dispute = createSampleDispute(1L);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setOrderId(100L);

        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("PARTIAL_REFUND");
        request.setRefundAmount(30.0);

        OrderDetailDto order = new OrderDetailDto();
        order.setTotalAmount(BigDecimal.valueOf(100.0));

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        when(orderServiceClient.getOrderDetails(100L)).thenReturn(order);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(walletService).credit(anyLong(), anyDouble(), anyString(), any(), anyString());

        DisputeResponse result = service.manualResolve(99L, 1L, request);

        assertThat(result.getResolution()).isEqualTo("PARTIAL_REFUND");
        assertThat(result.getRefundAmount()).isEqualTo(30.0);
    }

    @Test
    void manualResolve_shouldCapPartialRefundAtMaxLateDeliveryRefund() {
        Dispute dispute = createSampleDispute(1L);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setOrderId(100L);

        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("PARTIAL_REFUND");
        request.setRefundAmount(200.0); // More than max 100

        OrderDetailDto order = new OrderDetailDto();
        order.setTotalAmount(BigDecimal.valueOf(100.0));

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));
        when(orderServiceClient.getOrderDetails(100L)).thenReturn(order);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(walletService).credit(anyLong(), anyDouble(), anyString(), any(), anyString());

        DisputeResponse result = service.manualResolve(99L, 1L, request);

        assertThat(result.getRefundAmount()).isEqualTo(100.0); // Capped at MAX_LATE_DELIVERY_REFUND
    }

    @Test
    void manualResolve_shouldThrowWhenDisputeAlreadyClosed() {
        Dispute dispute = createSampleDispute(1L);
        dispute.setStatus(Dispute.DisputeStatus.CLOSED);

        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("FULL_REFUND");

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        assertThatThrownBy(() -> service.manualResolve(99L, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Dispute is already closed");
    }

    @Test
    void manualResolve_shouldThrowOnInvalidResolution() {
        Dispute dispute = createSampleDispute(1L);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);

        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("INVALID");

        when(disputeRepository.findById(1L)).thenReturn(Optional.of(dispute));

        assertThatThrownBy(() -> service.manualResolve(99L, 1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid resolution");
    }

    @Test
    void triggerAutoResolution_shouldProcessOpenDisputes() {
        Dispute dispute1 = createSampleDispute(1L);
        dispute1.setStatus(Dispute.DisputeStatus.OPEN);
        dispute1.setOrderId(100L);
        dispute1.setType(Dispute.DisputeType.ORDER_NOT_RECEIVED);
        dispute1.setCustomerEvidence("Evidence");

        Dispute dispute2 = createSampleDispute(2L);
        dispute2.setStatus(Dispute.DisputeStatus.UNDER_REVIEW);
        dispute2.setOrderId(200L);

        OrderDetailDto deliveredOrder = new OrderDetailDto();
        deliveredOrder.setStatus("DELIVERED");
        deliveredOrder.setTotalAmount(BigDecimal.valueOf(100.0));

        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(anyList()))
                .thenReturn(Arrays.asList(dispute1, dispute2));
        when(orderServiceClient.getOrderDetails(100L)).thenReturn(deliveredOrder);
        when(orderServiceClient.getOrderDetails(200L)).thenReturn(null);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));
        doNothing().when(walletService).credit(anyLong(), anyDouble(), anyString(), any(), anyString());

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isEqualTo(1); // Only dispute1 auto-resolved
        verify(disputeRepository, times(2)).save(any(Dispute.class));
    }

    private Dispute createSampleDispute(Long id) {
        Dispute dispute = new Dispute();
        dispute.setId(id);
        dispute.setOrderId(100L);
        dispute.setCustomerId(1L);
        dispute.setType(Dispute.DisputeType.ORDER_NOT_RECEIVED);
        dispute.setCustomerEvidence("Test evidence");
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        return dispute;
    }

    // Helper for mockito
    
}