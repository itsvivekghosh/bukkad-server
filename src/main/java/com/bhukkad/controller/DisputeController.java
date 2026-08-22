package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.DisputeRequest;
import com.bhukkad.dto.request.DisputeResolveRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.DisputeResponse;
import com.bhukkad.security.SecurityUtils;
import com.bhukkad.support.DisputeResolutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Customer and admin surfaces for evidence-based dispute resolution.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX)
@RequiredArgsConstructor
@Tag(name = "Dispute", description = "REST endpoints for Dispute")
public class DisputeController {

    private final DisputeResolutionService disputeResolutionService;
    private final SecurityUtils securityUtils;

    // ── Customer endpoints ───────────────────────────────────────────────────

    @PostMapping("/customers/orders/{orderId}/disputes")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "File dispute")
    public ResponseEntity<ApiResponse<DisputeResponse>> fileDispute(
            @PathVariable Long orderId, @Valid @RequestBody DisputeRequest request) {
        DisputeResponse dispute = disputeResolutionService.fileDispute(
                securityUtils.getCurrentUserId(), orderId, request);
        return ResponseEntity.ok(ApiResponse.success("Dispute filed", dispute));
    }

    @GetMapping("/customers/disputes")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<ApiResponse<List<DisputeResponse>>> myDisputes() {
        return ResponseEntity.ok(ApiResponse.success(
                disputeResolutionService.listForCustomer(securityUtils.getCurrentUserId())));
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping("/admin/disputes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<List<DisputeResponse>>> listDisputes() {
        return ResponseEntity.ok(ApiResponse.success(disputeResolutionService.listForAdmin()));
    }

    @GetMapping("/admin/disputes/{disputeId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<DisputeResponse>> getDispute(@PathVariable Long disputeId) {
        return ResponseEntity.ok(ApiResponse.success(disputeResolutionService.getById(disputeId)));
    }

    @PostMapping("/admin/disputes/{disputeId}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Resolve dispute")
    public ResponseEntity<ApiResponse<DisputeResponse>> resolveDispute(
            @PathVariable Long disputeId, @Valid @RequestBody DisputeResolveRequest request) {
        DisputeResponse dispute = disputeResolutionService.manualResolve(
                securityUtils.getCurrentUserId(), disputeId, request);
        return ResponseEntity.ok(ApiResponse.success("Dispute resolved", dispute));
    }

    @PostMapping("/admin/disputes/auto-resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<java.util.Map<String, Integer>>> autoResolve() {
        int resolved = disputeResolutionService.triggerAutoResolution();
        return ResponseEntity.ok(ApiResponse.success("Auto-resolution sweep completed",
                java.util.Map.of("resolved", resolved)));
    }
}
