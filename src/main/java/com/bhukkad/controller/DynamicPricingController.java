package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.DynamicPricingRuleRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DynamicPricingRuleResponse;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.service.DynamicPricingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/pricing")
@RequiredArgsConstructor
@Tag(name = "DynamicPricing", description = "REST endpoints for DynamicPricing")
public class DynamicPricingController {

    private final DynamicPricingService dynamicPricingService;

    @PostMapping("/restaurants/{restaurantId}/rules")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Create rule")
    public ResponseEntity<ApiResponse<DynamicPricingRuleResponse>> createRule(
            @PathVariable Long restaurantId,
            @Valid @RequestBody DynamicPricingRuleRequest request) {
        DynamicPricingRuleResponse rule = dynamicPricingService.createRule(restaurantId, request);
        return ResponseEntity.ok(ApiResponse.success("Pricing rule created successfully", rule));
    }

    @PutMapping("/rules/{ruleId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Update rule")
    public ResponseEntity<ApiResponse<DynamicPricingRuleResponse>> updateRule(
            @PathVariable Long ruleId,
            @Valid @RequestBody DynamicPricingRuleRequest request) {
        DynamicPricingRuleResponse rule = dynamicPricingService.updateRule(ruleId, request);
        return ResponseEntity.ok(ApiResponse.success("Pricing rule updated successfully", rule));
    }

    @DeleteMapping("/rules/{ruleId}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long ruleId) {
        dynamicPricingService.deleteRule(ruleId);
        return ResponseEntity.ok(ApiResponse.success("Pricing rule deleted successfully", null));
    }

    @GetMapping("/restaurants/{restaurantId}/rules")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Get rules")
    public ResponseEntity<ApiResponse<List<DynamicPricingRuleResponse>>> getRules(
            @PathVariable Long restaurantId) {
        List<DynamicPricingRuleResponse> rules = dynamicPricingService.getRulesByRestaurant(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @GetMapping("/restaurants/{restaurantId}/happy-hour")
    @RateLimited("order-track")
    public ResponseEntity<ApiResponse<Boolean>> isHappyHourActive(@PathVariable Long restaurantId) {
        boolean active = dynamicPricingService.isHappyHourActive(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(active));
    }
}