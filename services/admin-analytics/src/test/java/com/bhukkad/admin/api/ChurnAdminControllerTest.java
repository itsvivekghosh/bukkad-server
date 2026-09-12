package com.bhukkad.admin.api;
import com.bhukkad.admin.api.controller.ChurnAdminController;

import com.bhukkad.admin.domain.entity.ChurnScore;
import com.bhukkad.admin.domain.service.ChurnService;
import com.bhukkad.common.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChurnAdminControllerTest {

    @Mock private ChurnService churnService;
    @InjectMocks private ChurnAdminController controller;

    @Test
    void highRisk_mapsEntityToMonolithPayload() {
        ChurnScore score = new ChurnScore();
        score.setId(7L);
        score.setCustomerId(42L);
        score.setScore(0.812);
        score.setFeaturesJson("days_inactive=42|orders_declining");
        score.setComputedAt(LocalDateTime.of(2026, 9, 1, 3, 0));
        when(churnService.highRiskCustomers()).thenReturn(List.of(score));

        ResponseEntity<ApiResponse<List<ChurnAdminController.ChurnScoreResponse>>> response = controller.highRisk();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).hasSize(1);
        ChurnAdminController.ChurnScoreResponse payload = response.getBody().getData().get(0);
        assertThat(payload.id()).isEqualTo(7L);
        assertThat(payload.userId()).isEqualTo(42L);
        assertThat(payload.score()).isEqualTo(81);
        assertThat(payload.riskLevel()).isEqualTo("HIGH");
        assertThat(payload.factors()).isEqualTo("days_inactive=42|orders_declining");
        assertThat(payload.scoredAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 3, 0));
        assertThat(payload.retentionActionTaken()).isFalse();
    }

    @Test
    void highRisk_emptyResultReturnsEmptyList() {
        when(churnService.highRiskCustomers()).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<ChurnAdminController.ChurnScoreResponse>>> response = controller.highRisk();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).isNotNull().isEmpty();
    }

    @Test
    void rescore_returnsMonolithPayload() {
        ChurnScore score = new ChurnScore();
        score.setId(9L);
        score.setCustomerId(42L);
        score.setScore(0.6);
        score.setComputedAt(LocalDateTime.of(2026, 9, 5, 3, 0));
        when(churnService.rescore(42L)).thenReturn(score);

        ResponseEntity<ApiResponse<ChurnAdminController.ChurnScoreResponse>> response = controller.rescore(42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData().id()).isEqualTo(9L);
        assertThat(response.getBody().getData().userId()).isEqualTo(42L);
        assertThat(response.getBody().getData().score()).isEqualTo(60);
        assertThat(response.getBody().getData().riskLevel()).isEqualTo("MEDIUM");
        assertThat(response.getBody().getData().retentionActionTaken()).isFalse();
    }

    @Test
    void rescore_unknownCustomerReturnsNullDataLikeMonolith() {
        when(churnService.rescore(999L)).thenReturn(null);

        ResponseEntity<ApiResponse<ChurnAdminController.ChurnScoreResponse>> response = controller.rescore(999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getData()).isNull();
    }

    @Test
    void rescore_delegatesPathVariableToService() {
        when(churnService.rescore(12345L)).thenReturn(null);

        controller.rescore(12345L);

        verify(churnService).rescore(12345L);
    }

    @Test
    void endpoints_areMappedWithMonolithPathsAndMethods() throws Exception {
        Method highRisk = ChurnAdminController.class.getMethod("highRisk");
        Method rescore = ChurnAdminController.class.getMethod("rescore", Long.class);

        assertThat(highRisk.getAnnotation(GetMapping.class).value()).containsExactly("/high-risk");
        assertThat(rescore.getAnnotation(PostMapping.class).value()).containsExactly("/rescore/{userId}");
        assertThat(ChurnAdminController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/v1/admin/churn");
    }

    @Test
    void endpoints_carryClassLevelAdminGate() {
        // Security convention (post-audit): every admin-analytics admin
        // controller must declare a class-level @PreAuthorize("hasRole('ADMIN')").
        // Defense in depth — the gateway never routes /api/v1/admin/**, but the
        // service must not trust that alone.
        assertThat(ChurnAdminController.class.getAnnotation(PreAuthorize.class)).isNotNull();
    }
}
