package com.bhukkad.apikey;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Admin endpoints for partner API key lifecycle: create, list, revoke, rotate.
 */
@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/admin/api-keys")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class ApiKeyAdminController {

    private final ApiKeyService apiKeyService;

    @PostMapping
    public ResponseEntity<ApiResponse<ApiKeyService.CreatedApiKey>> create(
            @RequestBody CreateApiKeyRequest request) {
        return ResponseEntity.ok(ApiResponse.success("API key created",
                apiKeyService.create(request.name(), request.partnerId(), request.scopes(),
                        request.expiresAt())));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ApiKey>>> list() {
        return ResponseEntity.ok(ApiResponse.success(apiKeyService.list()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable Long id) {
        apiKeyService.revoke(id);
        return ResponseEntity.ok(ApiResponse.success("API key revoked", null));
    }

    @PostMapping("/{id}/rotate")
    public ResponseEntity<ApiResponse<ApiKeyService.CreatedApiKey>> rotate(
            @PathVariable Long id,
            @RequestBody(required = false) RotateApiKeyRequest request) {
        String scopes = request != null ? request.scopes() : null;
        LocalDateTime expiresAt = request != null ? request.expiresAt() : null;
        return ResponseEntity.ok(ApiResponse.success("API key rotated",
                apiKeyService.rotate(id, scopes, expiresAt)));
    }

    public record CreateApiKeyRequest(String name, Long partnerId, String scopes, LocalDateTime expiresAt) {
    }

    public record RotateApiKeyRequest(String scopes, LocalDateTime expiresAt) {
    }
}
