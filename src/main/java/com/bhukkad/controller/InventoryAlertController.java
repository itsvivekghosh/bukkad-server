package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.InventoryAlertResponse;
import com.bhukkad.service.InventoryAlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/inventory/alerts")
@RequiredArgsConstructor
public class InventoryAlertController {

    private final InventoryAlertService inventoryAlertService;

    @GetMapping("/restaurants/{restaurantId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<List<InventoryAlertResponse>>> getAlerts(@PathVariable Long restaurantId) {
        List<InventoryAlertResponse> alerts = inventoryAlertService.getAlertsByRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(alerts));
    }

    @PutMapping("/{alertId}/acknowledge")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> acknowledgeAlert(@PathVariable Long alertId) {
        inventoryAlertService.acknowledgeAlert(alertId);
        return ResponseEntity.ok(ApiResponse.success("Alert acknowledged", null));
    }
}