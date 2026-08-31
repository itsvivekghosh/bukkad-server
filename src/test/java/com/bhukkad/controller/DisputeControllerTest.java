package com.bhukkad.controller;

import com.bhukkad.dto.request.DisputeRequest;
import com.bhukkad.dto.request.DisputeResolveRequest;
import com.bhukkad.dto.response.DisputeResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.support.DisputeResolutionService;
import com.bhukkad.security.SecurityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisputeControllerTest {

    @Mock
    private DisputeResolutionService disputeResolutionService;
    @Mock
    private SecurityUtils securityUtils;

    @InjectMocks
    private DisputeController controller;

    private DisputeResponse disputeResponse;

    @BeforeEach
    void setUp() {
        disputeResponse = DisputeResponse.builder().id(1L).status("OPEN").build();
    }

    @Test
    void fileDispute() {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(disputeResolutionService.fileDispute(anyLong(), anyLong(), any(DisputeRequest.class)))
                .thenReturn(disputeResponse);
        DisputeRequest request = new DisputeRequest();

        ResponseEntity<ApiResponse<DisputeResponse>> resp = controller.fileDispute(1L, request);

        assertNotNull(resp);
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void myDisputes() {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(disputeResolutionService.listForCustomer(5L)).thenReturn(List.of(disputeResponse));

        ResponseEntity<ApiResponse<List<DisputeResponse>>> resp = controller.myDisputes();
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void listDisputes() {
        when(disputeResolutionService.listForAdmin()).thenReturn(List.of(disputeResponse));

        ResponseEntity<ApiResponse<List<DisputeResponse>>> resp = controller.listDisputes();
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    void getDispute() {
        when(disputeResolutionService.getById(3L)).thenReturn(disputeResponse);

        ResponseEntity<ApiResponse<DisputeResponse>> resp = controller.getDispute(3L);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(disputeResponse, resp.getBody().getData());
    }

    @Test
    void resolveDispute() {
        when(securityUtils.getCurrentUserId()).thenReturn(9L);
        DisputeResolveRequest request = new DisputeResolveRequest();
        request.setResolution("FULL_REFUND");
        request.setRefundAmount(250.0);
        when(disputeResolutionService.manualResolve(9L, 3L, request)).thenReturn(disputeResponse);

        ResponseEntity<ApiResponse<DisputeResponse>> resp = controller.resolveDispute(3L, request);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("Dispute resolved", resp.getBody().getMessage());
        assertEquals(disputeResponse, resp.getBody().getData());
    }

    @Test
    void autoResolve() {
        when(disputeResolutionService.triggerAutoResolution()).thenReturn(7);

        ResponseEntity<ApiResponse<Map<String, Integer>>> resp = controller.autoResolve();

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("Auto-resolution sweep completed", resp.getBody().getMessage());
        assertEquals(7, resp.getBody().getData().get("resolved"));
    }
}