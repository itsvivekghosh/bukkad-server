package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.CommissionTierResponse;
import com.bhukkad.service.CommissionTierService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/commission")
@RequiredArgsConstructor
public class CommissionTierController {

    private final CommissionTierService commissionTierService;

    @GetMapping("/restaurants/{restaurantId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<CommissionTierResponse>> getCommission(@PathVariable Long restaurantId) {
        CommissionTierResponse commission = commissionTierService.calculateCommission(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(commission));
    }

    @GetMapping("/tiers")
    public ResponseEntity<ApiResponse<List<CommissionTierResponse>>> getCommissionTiers() {
        List<CommissionTierResponse> tiers = commissionTierService.getCommissionTiers();
        return ResponseEntity.ok(ApiResponse.success(tiers));
    }
}