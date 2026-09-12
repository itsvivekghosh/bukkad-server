package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.service.FeatureFlagService;
import com.bhukkad.common.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin console for managing feature flags (port of monolith
 * {@code com.bhukkad.controller.FeatureFlagController} with exact
 * contract parity).
 * <p>
 * Provides read/write operations for feature flags. All operations are
 * restricted to users with the ADMIN role.
 */
@RestController
@RequestMapping("/api/v1/admin/feature-flags")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    /**
     * Returns the value of a specific feature flag.
     */
    @GetMapping("/{key}")
    public ResponseEntity<ApiResponse<Boolean>> getFlag(@PathVariable String key) {
        boolean value = featureFlagService.isEnabled(key);
        return ResponseEntity.ok(ApiResponse.success(value));
    }

    /**
     * Sets the value of a specific feature flag (admin kill-switch / rollout).
     * Passing {@code null} as the value reverts to the configured default.
     */
    @PutMapping("/{key}")
    public ResponseEntity<ApiResponse<Boolean>> setFlag(
            @PathVariable String key,
            @RequestParam(required = false) Boolean value) {
        featureFlagService.setFlag(key, value);
        boolean current = featureFlagService.isEnabled(key);
        return ResponseEntity.ok(ApiResponse.success(current));
    }

    /**
     * Returns all feature flags and their current effective values.
     */
    @GetMapping("")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> getAllFlags() {
        Map<String, Boolean> flags = featureFlagService.snapshot();
        return ResponseEntity.ok(ApiResponse.success(flags));
    }
}
