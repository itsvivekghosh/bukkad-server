package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.SubscriptionPlanRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.SubscriptionPlanResponse;
import com.bhukkad.order.SubscriptionService;
import com.bhukkad.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/customers/subscriptions")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Subscriptions", description = "REST endpoints for recurring subscription meal plans")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;
    private final SecurityUtils securityUtils;

    @PostMapping
    @Operation(summary = "Create a weekly subscription plan")
    public ResponseEntity<ApiResponse<SubscriptionPlanResponse>> create(
            @Valid @RequestBody SubscriptionPlanRequest request) {
        SubscriptionPlanResponse response = subscriptionService.createPlan(
                securityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.success("Subscription plan created", response));
    }

    @GetMapping
    @Operation(summary = "List my subscription plans")
    public ResponseEntity<ApiResponse<List<SubscriptionPlanResponse>>> list() {
        List<SubscriptionPlanResponse> plans = subscriptionService.listPlans(
                securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(plans));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a subscription plan")
    public ResponseEntity<ApiResponse<SubscriptionPlanResponse>> pause(@PathVariable @Positive Long id) {
        SubscriptionPlanResponse response = subscriptionService.pausePlan(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Subscription plan paused", response));
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a paused subscription plan")
    public ResponseEntity<ApiResponse<SubscriptionPlanResponse>> resume(@PathVariable @Positive Long id) {
        SubscriptionPlanResponse response = subscriptionService.resumePlan(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Subscription plan resumed", response));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a subscription plan")
    public ResponseEntity<ApiResponse<SubscriptionPlanResponse>> cancel(@PathVariable @Positive Long id) {
        SubscriptionPlanResponse response = subscriptionService.cancelPlan(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Subscription plan cancelled", response));
    }

    @PostMapping("/{id}/skip")
    @Operation(summary = "Skip the next delivery of a subscription plan")
    public ResponseEntity<ApiResponse<SubscriptionPlanResponse>> skipNext(@PathVariable @Positive Long id) {
        SubscriptionPlanResponse response = subscriptionService.skipNextDelivery(
                id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Next delivery skipped", response));
    }
}
