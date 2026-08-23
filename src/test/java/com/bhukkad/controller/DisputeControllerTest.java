package com.bhukkad.controller;

import com.bhukkad.dto.request.DisputeRequest;
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
        assertEquals(200, resp.getStatusCodeValue());
    }

    @Test
    void myDisputes() {
        when(securityUtils.getCurrentUserId()).thenReturn(5L);
        when(disputeResolutionService.listForCustomer(5L)).thenReturn(List.of(disputeResponse));

        ResponseEntity<ApiResponse<List<DisputeResponse>>> resp = controller.myDisputes();
        assertEquals(200, resp.getStatusCodeValue());
    }

    @Test
    void listDisputes() {
        when(disputeResolutionService.listForAdmin()).thenReturn(List.of(disputeResponse));

        ResponseEntity<ApiResponse<List<DisputeResponse>>> resp = controller.listDisputes();
        assertEquals(200, resp.getStatusCodeValue());
    }
}