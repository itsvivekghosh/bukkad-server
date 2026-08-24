package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.InventoryAlertResponse;
import com.bhukkad.service.InventoryAlertService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryAlertControllerTest {

    @Mock
    private InventoryAlertService inventoryAlertService;

    @InjectMocks
    private InventoryAlertController controller;

    @Test
    void getAlerts_returnsAlertsForRestaurant() {
        List<InventoryAlertResponse> alerts = List.of(InventoryAlertResponse.builder().build());
        when(inventoryAlertService.getAlertsByRestaurant(1L)).thenReturn(alerts);

        ResponseEntity<ApiResponse<List<InventoryAlertResponse>>> response = controller.getAlerts(1L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(alerts, response.getBody().getData());
    }

    @Test
    void acknowledgeAlert_returnsSuccess() {
        ResponseEntity<ApiResponse<Void>> response = controller.acknowledgeAlert(9L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Alert acknowledged", response.getBody().getMessage());
        verify(inventoryAlertService).acknowledgeAlert(9L);
    }
}
