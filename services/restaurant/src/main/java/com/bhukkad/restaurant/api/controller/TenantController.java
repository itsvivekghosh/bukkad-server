package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.entity.Tenant;
import com.bhukkad.restaurant.domain.service.impl.TenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.bhukkad.restaurant.api.dto.response.ApiResponse;

/**
 * Admin CRUD for white-label B2B tenants (Batch 4 wave 2 migration).
 *
 * <p>Restaurant-service port of the monolith
 * {@code com.bhukkad.controller.TenantController}. The service-local contract
 * exchanges {@link Tenant} entities directly (no DTO layer yet); the monolith
 * keeps a working copy with the DTO-based contract until the gateway flips.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class TenantController {

    private final TenantService tenantService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Tenant>>> list() {
        return ResponseEntity.ok(ApiResponse.success(tenantService.listAll()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Tenant>> create(@RequestBody Tenant tenant) {
        return ResponseEntity.ok(ApiResponse.success("Tenant created", tenantService.create(tenant)));
    }

    @PutMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<Tenant>> update(
            @PathVariable Long tenantId, @RequestBody Tenant patch) {
        return ResponseEntity.ok(ApiResponse.success("Tenant updated",
                tenantService.update(tenantId, patch)));
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long tenantId) {
        tenantService.deactivate(tenantId);
        return ResponseEntity.ok(ApiResponse.success("Tenant deactivated", null));
    }
}
