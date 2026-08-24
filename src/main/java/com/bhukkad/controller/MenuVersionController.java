package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.MenuVersionResponse;
import com.bhukkad.menu.MenuVersionService;
import com.bhukkad.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/menu/versions")
@RequiredArgsConstructor
@Tag(name = "Menu Versions", description = "REST endpoints for menu versioning and preview")
public class MenuVersionController {

    private final MenuVersionService menuVersionService;
    private final SecurityUtils securityUtils;

    @PostMapping
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Create a draft version from the current live menu")
    public ResponseEntity<ApiResponse<MenuVersionResponse>> createDraft(
            @RequestParam Long restaurantId,
            @RequestParam(required = false) String label) {
        MenuVersionResponse version = menuVersionService.createDraft(
                restaurantId, securityUtils.getCurrentUserId(), label);
        return ResponseEntity.ok(ApiResponse.success("Menu draft version created", version));
    }

    @GetMapping
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "List menu versions for a restaurant")
    public ResponseEntity<ApiResponse<List<MenuVersionResponse>>> listVersions(
            @RequestParam Long restaurantId) {
        List<MenuVersionResponse> versions = menuVersionService.listVersions(
                restaurantId, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(versions));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Preview a menu version snapshot")
    public ResponseEntity<ApiResponse<Map<String, Object>>> preview(@PathVariable Long id) {
        Map<String, Object> snapshot = menuVersionService.preview(id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success(snapshot));
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('RESTAURANT_OWNER')")
    @Operation(summary = "Publish a menu version")
    public ResponseEntity<ApiResponse<MenuVersionResponse>> publish(@PathVariable Long id) {
        MenuVersionResponse version = menuVersionService.publish(id, securityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.success("Menu version published", version));
    }
}