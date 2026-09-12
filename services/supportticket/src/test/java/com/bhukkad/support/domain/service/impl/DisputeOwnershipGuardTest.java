package com.bhukkad.support.domain.service.impl;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.common.error.UpstreamUnavailableException;
import com.bhukkad.support.infrastructure.client.OrderServiceClient;
import com.bhukkad.support.dto.OrderDetailDto;
import com.bhukkad.support.dto.request.DisputeRequest;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.domain.entity.Dispute;
import com.bhukkad.support.domain.repository.DisputeRepository;
import com.bhukkad.support.infrastructure.client.WalletCreditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit CRITICAL-IDOR-1: dispute filing is an ownership oracle + money path.
 * A customer must not be able to file a dispute against someone else's order
 * (and thereby auto-refund that order's total into their own wallet), the
 * oracle failing must fail closed, and auto-resolution must never credit a
 * wallet other than the verified order owner's.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeOwnershipGuardTest {

    private static final Long ORDER_ID = 42L;
    private static final Long OWNER_ID = 7L;
    private static final Long ATTACKER_ID = 8L;

    @Mock private DisputeRepository disputeRepository;
    @Mock private WalletCreditClient walletClient;
    @Mock private OrderServiceClient orderClient;
    private DisputeResolutionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DisputeResolutionServiceImpl(disputeRepository, walletClient, orderClient);
        ReflectionTestUtils.setField(service, "lateThresholdMinutes", 30L);
    }

    private DisputeRequest request(String type) {
        DisputeRequest request = new DisputeRequest();
        request.setType(type);
        request.setCustomerEvidence("package never arrived, screenshot attached");
        return request;
    }

    private OrderDetailDto order(String customerId, String status) {
        OrderDetailDto o = new OrderDetailDto();
        o.setOrderId(ORDER_ID);
        o.setCustomerId(customerId == null ? null : Long.valueOf(customerId));
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal("250.00"));
        return o;
    }

    @Test
    void fileDispute_nonOwner_rejectedBeforeAnythingIsPersisted() {
        when(orderClient.getOrderCustomerId(ORDER_ID)).thenReturn(OWNER_ID);

        assertThatThrownBy(() -> service.fileDispute(ATTACKER_ID, ORDER_ID, request("ORDER_NOT_RECEIVED")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("does not belong");

        verify(disputeRepository, never()).existsByOrderId(any());
        verify(disputeRepository, never()).save(any(Dispute.class));
        verify(walletClient, never()).credit(any(), anyDouble(), anyString(), any(), anyString());
    }

    @Test
    void fileDispute_unknownOrder_answers404() {
        when(orderClient.getOrderCustomerId(ORDER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.fileDispute(OWNER_ID, ORDER_ID, request("ORDER_NOT_RECEIVED")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Order not found");

        verify(disputeRepository, never()).save(any(Dispute.class));
    }

    @Test
    void fileDispute_oracleOutage_failsClosed() {
        when(orderClient.getOrderCustomerId(ORDER_ID))
                .thenThrow(new UpstreamUnavailableException("order",
                        new RuntimeException("connection refused")));

        assertThatThrownBy(() -> service.fileDispute(OWNER_ID, ORDER_ID, request("ORDER_NOT_RECEIVED")))
                .isInstanceOf(UpstreamUnavailableException.class);

        verify(disputeRepository, never()).save(any(Dispute.class));
        verify(walletClient, never()).credit(any(), anyDouble(), anyString(), any(), anyString());
    }

    @Test
    void fileDispute_verifiedOwner_proceeds() {
        when(orderClient.getOrderCustomerId(ORDER_ID)).thenReturn(OWNER_ID);
        when(disputeRepository.existsByOrderId(ORDER_ID)).thenReturn(false);
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> {
            Dispute d = inv.getArgument(0);
            d.setId(1L);
            return d;
        });
        // Not delivered → not auto-refundable; the ownership gate is the point.
        when(orderClient.getOrderDetails(ORDER_ID)).thenReturn(order("7", "PLACED"));

        DisputeResponse response = service.fileDispute(OWNER_ID, ORDER_ID, request("ORDER_NOT_RECEIVED"));

        assertThat(response).isNotNull();
        // Initial persist + post-auto-resolution persist.
        verify(disputeRepository, org.mockito.Mockito.times(2)).save(any(Dispute.class));
        verify(walletClient, never()).credit(any(), anyDouble(), anyString(), any(), anyString());
    }

    @Test
    void autoResolution_refundOnlyHitsVerifiedOwner() {
        // Sweep path: a dispute row claiming customerId 8 on an order whose
        // detail feed says the owner is 7 — no money may move.
        Dispute dispute = new Dispute();
        dispute.setId(5L);
        dispute.setCustomerId(ATTACKER_ID);
        dispute.setOrderId(ORDER_ID);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setType(Dispute.DisputeType.ORDER_NOT_RECEIVED);
        dispute.setCustomerEvidence("evidence");
        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(java.util.List.of(dispute));
        when(orderClient.getOrderDetails(ORDER_ID)).thenReturn(order("7", "DELIVERED"));

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isZero();
        assertThat(dispute.getStatus()).isEqualTo(Dispute.DisputeStatus.UNDER_REVIEW);
        verify(walletClient, never()).credit(eq(ATTACKER_ID), anyDouble(), anyString(), any(), anyString());
        verify(walletClient, never()).credit(eq(OWNER_ID), anyDouble(), anyString(), any(), anyString());
    }

    @Test
    void autoResolution_matchingOwner_isEligible() {
        Dispute dispute = new Dispute();
        dispute.setId(5L);
        dispute.setCustomerId(OWNER_ID);
        dispute.setOrderId(ORDER_ID);
        dispute.setStatus(Dispute.DisputeStatus.OPEN);
        dispute.setType(Dispute.DisputeType.ORDER_NOT_RECEIVED);
        dispute.setCustomerEvidence("evidence");
        when(disputeRepository.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(java.util.List.of(dispute));
        when(orderClient.getOrderDetails(ORDER_ID)).thenReturn(order("7", "DELIVERED"));
        when(disputeRepository.save(any(Dispute.class))).thenAnswer(inv -> inv.getArgument(0));

        int resolved = service.triggerAutoResolution();

        assertThat(resolved).isEqualTo(1);
        assertThat(dispute.getStatus()).isEqualTo(Dispute.DisputeStatus.AUTO_RESOLVED);
        verify(walletClient).credit(eq(OWNER_ID), eq(250.0), eq("42"), isNull(), eq("dispute-refund"));
    }
}
