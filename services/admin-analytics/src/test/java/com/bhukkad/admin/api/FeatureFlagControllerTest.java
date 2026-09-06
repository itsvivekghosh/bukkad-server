package com.bhukkad.admin.api;

import com.bhukkad.admin.service.FeatureFlagService;
import com.bhukkad.common.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeatureFlagControllerTest {

    @Mock private FeatureFlagService featureFlagService;
    @InjectMocks private FeatureFlagController controller;

    @Test
    void getFlag_returnsValueInSuccessEnvelope() {
        when(featureFlagService.isEnabled("kill-switch")).thenReturn(true);

        ResponseEntity<ApiResponse<Boolean>> response = controller.getFlag("kill-switch");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).isTrue();
    }

    @Test
    void getFlag_unknownKey_resolvesToFalseWith200_matchingMonolithSemantics() {
        // Monolith parity: unknown flags are NOT 404. isEnabled falls back to
        // the configured default (false) and the endpoint returns 200.
        when(featureFlagService.isEnabled("no-such-flag")).thenReturn(false);

        ResponseEntity<ApiResponse<Boolean>> response = controller.getFlag("no-such-flag");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).isFalse();
    }

    @Test
    void setFlag_updatesOverrideAndReturnsCurrentEffectiveValue() {
        when(featureFlagService.isEnabled("kill-switch")).thenReturn(true);

        ResponseEntity<ApiResponse<Boolean>> response = controller.setFlag("kill-switch", true);

        verify(featureFlagService).setFlag("kill-switch", true);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData()).isTrue();
    }

    @Test
    void setFlag_nullValue_revertsToConfiguredDefault() {
        when(featureFlagService.isEnabled("kill-switch")).thenReturn(false);

        ResponseEntity<ApiResponse<Boolean>> response = controller.setFlag("kill-switch", null);

        verify(featureFlagService).setFlag("kill-switch", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isFalse();
    }

    @Test
    void setFlag_publishesInvalidationViaServiceBeforePostWriteRead() {
        // The controller delegates cache invalidation to the service mutation
        // (setFlag writes through and broadcasts on the changed channel),
        // then re-reads via isEnabled exactly like the monolith.
        controller.setFlag("kill-switch", true);

        InOrder inOrder = inOrder(featureFlagService);
        inOrder.verify(featureFlagService).setFlag("kill-switch", true);
        inOrder.verify(featureFlagService).isEnabled("kill-switch");
    }

    @Test
    void getAllFlags_returnsSnapshotInSuccessEnvelope() {
        Map<String, Boolean> snapshot = new LinkedHashMap<>();
        snapshot.put("kill-switch", true);
        snapshot.put("dark-mode", false);
        when(featureFlagService.snapshot()).thenReturn(snapshot);

        ResponseEntity<ApiResponse<Map<String, Boolean>>> response = controller.getAllFlags();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData())
                .containsEntry("kill-switch", true)
                .containsEntry("dark-mode", false)
                .hasSize(2);
    }

    @Test
    void classLevelPreAuthorize_mirrorsMonolithAdminRoleConvention() {
        PreAuthorize preAuthorize = FeatureFlagController.class.getAnnotation(PreAuthorize.class);

        assertThat(preAuthorize).as("class-level @PreAuthorize").isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('ADMIN')");
    }
}
