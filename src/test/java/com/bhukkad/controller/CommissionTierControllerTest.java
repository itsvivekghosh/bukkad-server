package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CommissionTierResponse;
import com.bhukkad.service.CommissionTierService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommissionTierControllerTest {

    @Mock
    private CommissionTierService commissionTierService;

    @InjectMocks
    private CommissionTierController controller;

    @Test
    void getCommission_returnsCalculatedCommission() {
        CommissionTierResponse commission = CommissionTierResponse.builder().build();
        when(commissionTierService.calculateCommission(1L)).thenReturn(commission);

        ResponseEntity<ApiResponse<CommissionTierResponse>> response = controller.getCommission(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(commission, response.getBody().getData());
    }

    @Test
    void getCommissionTiers_returnsAllTiers() {
        List<CommissionTierResponse> tiers = List.of(CommissionTierResponse.builder().build());
        when(commissionTierService.getCommissionTiers()).thenReturn(tiers);

        ResponseEntity<ApiResponse<List<CommissionTierResponse>>> response = controller.getCommissionTiers();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(tiers, response.getBody().getData());
    }
}
