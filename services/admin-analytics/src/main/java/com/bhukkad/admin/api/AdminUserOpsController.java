package com.bhukkad.admin.api;

import com.bhukkad.admin.audit.AuditService;
import com.bhukkad.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Platform-admin user + city operations. Identity-owned mutations (verify /
 * activate / erase) and the delivery-owned city registry live on the services
 * that own those tables — this controller is the ADMIN-gated proxy the ops
 * console calls, forwarding over the service mesh with the shared service
 * token (both peers run {@code ServiceJwtAuthFilter}).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserOpsController {

    private final AuditService auditService;
    private final ServiceMeshClient mesh;

    /** Paginated user directory, proxied to identity over the mesh. */
    @GetMapping("/users")
    public Map<String, Object> users(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size) {
        return mesh.get("/api/v1/internal/admin/users?page=" + page + "&size=" + size);
    }

    @GetMapping("/users/{userId}")
    public Map<String, Object> user(@PathVariable Long userId) {
        return mesh.get("/api/v1/internal/admin/users/" + userId);
    }

    /** Marks a restaurant-owner account verified. */
    @PutMapping("/owners/{userId}/verify")
    public Map<String, Object> verifyOwner(@PathVariable Long userId) {
        Map<String, Object> result = mesh.put(
                "/api/v1/internal/admin/users/" + userId + "/verify?expectedRole=RESTAURANT_OWNER");
        auditService.record("ACCOUNT_VERIFIED", "USER", String.valueOf(userId), null, null);
        result.put("message", "Restaurant owner verified");
        return result;
    }

    /** Marks a delivery-agent account verified. */
    @PutMapping("/agents/{userId}/verify")
    public Map<String, Object> verifyAgent(@PathVariable Long userId) {
        Map<String, Object> result = mesh.put(
                "/api/v1/internal/admin/users/" + userId + "/verify?expectedRole=DELIVERY_AGENT");
        auditService.record("ACCOUNT_VERIFIED", "USER", String.valueOf(userId), null, null);
        result.put("message", "Delivery agent verified");
        return result;
    }

    @PutMapping("/users/{userId}/activate")
    public Map<String, Object> activate(@PathVariable Long userId) {
        Map<String, Object> result = mesh.put("/api/v1/internal/admin/users/" + userId + "/activate");
        auditService.record("USER_ACTIVATED", "USER", String.valueOf(userId), null, null);
        result.put("message", "Account activated");
        return result;
    }

    @PutMapping("/users/{userId}/deactivate")
    public Map<String, Object> deactivate(@PathVariable Long userId) {
        Map<String, Object> result = mesh.put("/api/v1/internal/admin/users/" + userId + "/deactivate");
        auditService.record("USER_DEACTIVATED", "USER", String.valueOf(userId), null, null);
        result.put("message", "Account deactivated");
        return result;
    }

    /** GDPR erasure entry point for the admin compliance console. */
    @PostMapping("/users/{userId}/erase")
    public Map<String, Object> erase(@PathVariable Long userId) {
        Map<String, Object> result = mesh.post("/api/v1/internal/admin/users/" + userId + "/erase", null);
        auditService.record("USER_ERASED", "USER", String.valueOf(userId), null, null);
        result.put("message", "User data erased");
        return result;
    }

    // ------------------------------------------------------------------
    // City registry (proxied to delivery)
    // ------------------------------------------------------------------

    @GetMapping("/cities")
    public ResponseEntity<?> cities() {
        return ResponseEntity.status(mesh.getStatus("/api/v1/internal/cities"))
                .body(mesh.getRaw("/api/v1/internal/cities"));
    }

    @PostMapping("/cities")
    public ResponseEntity<Map<String, Object>> createCity(
            @RequestBody(required = false) Map<String, Object> body) {
        String name = body == null ? null : (String) body.get("name");
        if (name == null || name.isBlank()) {
            throw new BusinessException("name is required");
        }
        return mesh.postForEntity("/api/v1/internal/cities", Map.of("name", name.trim()));
    }

    // ------------------------------------------------------------------
    // Mesh client: identity/delivery internal endpoints with service token
    // ------------------------------------------------------------------

    @Component
    @RequiredArgsConstructor
    static class ServiceMeshClient {
        private final RestClient.Builder restBuilder;
        private final com.bhukkad.common.security.ServiceJwtAuthTokenProvider tokenProvider;

        @org.springframework.beans.factory.annotation.Value("${app.routes.identity-uri:http://identity:8080}")
        private String identityUri;

        @org.springframework.beans.factory.annotation.Value("${app.routes.delivery-uri:http://delivery:8080}")
        private String deliveryUri;

        private RestClient client(String base) {
            var spec = restBuilder.baseUrl(base);
            String token = tokenProvider.serviceToken();
            if (token != null && !token.isBlank()) {
                spec = spec.defaultHeader("X-Service-Token", token);
            }
            return spec.build();
        }

        Map<String, Object> get(String path) {
            return client(identityUri).get().uri(path)
                    .retrieve().body(java.util.Map.class);
        }

        Object getRaw(String path) {
            return client(deliveryUri).get().uri(path)
                    .retrieve().body(Object.class);
        }

        int getStatus(String path) {
            return client(deliveryUri).get().uri(path)
                    .retrieve().toBodilessEntity().getStatusCode().value();
        }

        Map<String, Object> put(String path) {
            return client(identityUri).put().uri(path)
                    .retrieve().body(java.util.Map.class);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Map<String, Object> post(String path, Map<String, Object> body) {
            return client(identityUri).post().uri(path)
                    .body(body == null ? Map.of() : body)
                    .retrieve().body(java.util.Map.class);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        ResponseEntity<Map<String, Object>> postForEntity(String path, Map<String, Object> body) {
            ResponseEntity<Map> raw = client(deliveryUri).post().uri(path)
                    .body(body == null ? Map.of() : body)
                    .retrieve().toEntity(java.util.Map.class);
            return new ResponseEntity<>((Map<String, Object>) raw.getBody(), raw.getStatusCode());
        }
    }
}
