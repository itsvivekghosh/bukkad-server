package com.bhukkad.apikey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAdminControllerTest {

    @Mock
    private ApiKeyService apiKeyService;

    @InjectMocks
    private ApiKeyAdminController controller;

    @Test void create_returnsCreatedKey() {
        var created = new ApiKeyService.CreatedApiKey(1L, "Partner", "bhk_abc_secret", "orders:read", null);
        when(apiKeyService.create("Partner", 7L, "orders:read", null)).thenReturn(created);

        ResponseEntity<com.bhukkad.dto.response.ApiResponse<ApiKeyService.CreatedApiKey>> resp =
                controller.create(new ApiKeyAdminController.CreateApiKeyRequest("Partner", 7L, "orders:read", null));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("bhk_abc_secret", resp.getBody().getData().apiKey());
        verify(apiKeyService).create("Partner", 7L, "orders:read", null);
    }

    @Test void create_withExpiry_passesExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2026, 12, 31, 23, 59);
        var created = new ApiKeyService.CreatedApiKey(2L, "X", "bhk_x", null, expiry);
        when(apiKeyService.create("X", null, null, expiry)).thenReturn(created);

        ResponseEntity<com.bhukkad.dto.response.ApiResponse<ApiKeyService.CreatedApiKey>> resp =
                controller.create(new ApiKeyAdminController.CreateApiKeyRequest("X", null, null, expiry));

        assertEquals("bhk_x", resp.getBody().getData().apiKey());
    }

    @Test void list_returnsAllKeys() {
        ApiKey key = new ApiKey();
        key.setId(1L);
        when(apiKeyService.list()).thenReturn(List.of(key));

        ResponseEntity<com.bhukkad.dto.response.ApiResponse<List<ApiKey>>> resp = controller.list();

        assertEquals(1, resp.getBody().getData().size());
        assertEquals(1L, resp.getBody().getData().get(0).getId());
    }

    @Test void revoke_delegatesToService() {
        ResponseEntity<com.bhukkad.dto.response.ApiResponse<Void>> resp = controller.revoke(9L);
        assertEquals(200, resp.getStatusCode().value());
        verify(apiKeyService).revoke(9L);
    }

    @Test void rotate_withRequest_delegatesScopesAndExpiry() {
        LocalDateTime expiry = LocalDateTime.of(2027, 1, 1, 0, 0);
        var created = new ApiKeyService.CreatedApiKey(3L, "P", "bhk_new", "orders:write", expiry);
        when(apiKeyService.rotate(3L, "orders:write", expiry)).thenReturn(created);

        ResponseEntity<com.bhukkad.dto.response.ApiResponse<ApiKeyService.CreatedApiKey>> resp =
                controller.rotate(3L, new ApiKeyAdminController.RotateApiKeyRequest("orders:write", expiry));

        assertEquals("bhk_new", resp.getBody().getData().apiKey());
        verify(apiKeyService).rotate(3L, "orders:write", expiry);
    }

    @Test void rotate_withoutRequest_delegatesNulls() {
        var created = new ApiKeyService.CreatedApiKey(4L, "P", "bhk_rot", null, null);
        when(apiKeyService.rotate(4L, null, null)).thenReturn(created);

        ResponseEntity<com.bhukkad.dto.response.ApiResponse<ApiKeyService.CreatedApiKey>> resp =
                controller.rotate(4L, null);

        assertNotNull(resp.getBody());
        verify(apiKeyService).rotate(4L, null, null);
    }
}
