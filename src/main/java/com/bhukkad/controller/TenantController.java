package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.request.TenantRequest;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.TenantResponse;
import com.bhukkad.tenant.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Admin CRUD for white-label B2B tenants, plus a public storefront config lookup.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Tenant", description = "REST endpoints for Tenant")
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<TenantResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(tenantService.listAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TenantResponse>> create(@Valid @RequestBody TenantRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Tenant created", tenantService.create(request)));
    }

    @PutMapping("/{tenantId}")
    @Operation(summary = "Update")
    public ResponseEntity<ApiResponse<TenantResponse>> update(
            @PathVariable Long tenantId, @Valid @RequestBody TenantRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Tenant updated",
                tenantService.update(tenantId, request)));
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long tenantId) {
        tenantService.deactivate(tenantId);
        return ResponseEntity.ok(ApiResponse.success("Tenant deactivated", null));
    }
}
