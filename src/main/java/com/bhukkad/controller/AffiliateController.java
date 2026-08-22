package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.AffiliateCodeRequest;
import com.bhukkad.dto.response.AffiliateCodeResponse;
import com.bhukkad.dto.response.AffiliateStatsResponse;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.referral.AffiliateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin management of influencer/affiliate codes and referral tracking.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin/affiliates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Affiliate", description = "REST endpoints for Affiliate")
public class AffiliateController {

    private final AffiliateService affiliateService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AffiliateCodeResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(affiliateService.listAll()));
    }

    @PostMapping
    @Operation(summary = "Create")
    public ResponseEntity<ApiResponse<AffiliateCodeResponse>> create(
            @Valid @RequestBody AffiliateCodeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Affiliate code created",
                affiliateService.create(request)));
    }

    @PutMapping("/{affiliateId}")
    @Operation(summary = "Update")
    public ResponseEntity<ApiResponse<AffiliateCodeResponse>> update(
            @PathVariable Long affiliateId, @Valid @RequestBody AffiliateCodeRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Affiliate code updated",
                affiliateService.update(affiliateId, request)));
    }

    @DeleteMapping("/{affiliateId}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long affiliateId) {
        affiliateService.deactivate(affiliateId);
        return ResponseEntity.ok(ApiResponse.success("Affiliate code deactivated", null));
    }

    @GetMapping("/{affiliateId}/stats")
    public ResponseEntity<ApiResponse<AffiliateStatsResponse>> stats(@PathVariable Long affiliateId) {
        return ResponseEntity.ok(ApiResponse.success(affiliateService.getStats(affiliateId)));
    }
}
