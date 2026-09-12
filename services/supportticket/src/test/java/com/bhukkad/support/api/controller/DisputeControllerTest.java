package com.bhukkad.support.api.controller;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.support.dto.request.DisputeRequest;
import com.bhukkad.support.dto.request.DisputeResolveRequest;
import com.bhukkad.support.dto.response.DisputeResponse;
import com.bhukkad.support.domain.service.impl.DisputeResolutionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisputeControllerTest {

    @Mock
    private DisputeResolutionServiceImpl disputeResolutionService;

    private DisputeController controller;

    @BeforeEach
    void setUp() {
        controller = new DisputeController(disputeResolutionService);
    }

    @Test
    void fileDispute_shouldCreateDisputeFromValidRequest() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        DisputeRequest request = new DisputeRequest();
        request.setType("ORDER_NOT_RECEIVED");
        request.setCustomerEvidence("Photo of empty box");

        DisputeResponse expected = new DisputeResponse(
                1L, 100L, null, "ORDER_NOT_RECEIVED", "OPEN",
                "Photo of empty box", null, null, null, null,
                null, null, null, null
        );

        when(disputeResolutionService.fileDispute(1L, 100L, request)).thenReturn(expected);

        ResponseEntity<DisputeResponse> response = controller.fileDispute(principal, 100L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getType()).isEqualTo("ORDER_NOT_RECEIVED");
        assertThat(response.getBody().getCustomerEvidence()).isEqualTo("Photo of empty box");
    }

    @Test
    void myDisputes_shouldReturnCustomerDisputes() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        DisputeResponse dispute = new DisputeResponse(
                1L, 100L, null, "ORDER_NOT_RECEIVED", "OPEN",
                "Evidence", null, null, null, null,
                null, null, null, null
        );

        when(disputeResolutionService.listForCustomer(1L)).thenReturn(Collections.singletonList(dispute));

        ResponseEntity<List<DisputeResponse>> response = controller.myDisputes(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void listDisputes_shouldReturnAllDisputesForAdmin() {
        DisputeResponse dispute = new DisputeResponse(
                1L, 100L, null, "ORDER_NOT_RECEIVED", "OPEN",
                "Evidence", null, null, null, null,
                null, null, null, null
        );

        when(disputeResolutionService.listForAdmin()).thenReturn(Arrays.asList(dispute));

        ResponseEntity<List<DisputeResponse>> response = controller.listDisputes();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getDispute_shouldReturnDisputeById() {
        DisputeResponse expected = new DisputeResponse(
                1L, 100L, null, "ORDER_NOT_RECEIVED", "OPEN",
                "Evidence", null, null, null, null,
                null, null, null, null
        );

        when(disputeResolutionService.getById(1L)).thenReturn(expected);

        ResponseEntity<DisputeResponse> response = controller.getDispute(1L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getId()).isEqualTo(1L);
    }

    @Test
    void resolveDispute_shouldResolveWithTokenPrincipal() {
        TokenPrincipal principal = new TokenPrincipal(99L, "admin@example.com", "ADMIN");
        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("FULL_REFUND");
        request.setRefundAmount(50.0);
        request.setNotes("Resolved");

        DisputeResponse expected = new DisputeResponse(
                1L, 100L, null, "ORDER_NOT_RECEIVED", "MANUAL_RESOLVED",
                "Evidence", null, null, "Resolved", "FULL_REFUND",
                50.0, 99L, null, null
        );

        when(disputeResolutionService.manualResolve(99L, 1L, request)).thenReturn(expected);

        ResponseEntity<DisputeResponse> response = controller.resolveDispute(principal, 1L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getResolution()).isEqualTo("FULL_REFUND");
        assertThat(response.getBody().getResolvedBy()).isEqualTo(99L);
    }

    @Test
    void resolveDispute_shouldUseZeroForServicePrincipal() {
        String servicePrincipal = "service-principal";
        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("FULL_REFUND");
        request.setRefundAmount(50.0);

        DisputeResponse expected = new DisputeResponse(
                1L, 100L, null, "ORDER_NOT_RECEIVED", "MANUAL_RESOLVED",
                "Evidence", null, null, "Resolved", "FULL_REFUND",
                50.0, 0L, null, null
        );

        when(disputeResolutionService.manualResolve(0L, 1L, request)).thenReturn(expected);

        ResponseEntity<DisputeResponse> response = controller.resolveDispute(servicePrincipal, 1L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getResolvedBy()).isEqualTo(0L);
    }

    @Test
    void autoResolve_shouldReturnResolvedCount() {
        when(disputeResolutionService.triggerAutoResolution()).thenReturn(5);

        ResponseEntity<Map<String, Integer>> response = controller.autoResolve();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().get("resolved")).isEqualTo(5);
    }
}