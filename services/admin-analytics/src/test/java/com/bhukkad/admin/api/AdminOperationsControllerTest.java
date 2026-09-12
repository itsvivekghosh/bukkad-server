package com.bhukkad.admin.api;
import com.bhukkad.admin.api.controller.AdminOperationsController;

import com.bhukkad.admin.domain.entity.AnalyticsExportTask;
import com.bhukkad.admin.domain.entity.FraudReviewAction;
import com.bhukkad.admin.domain.service.AdminQueryService;
import com.bhukkad.admin.domain.service.ApiKeyService;
import com.bhukkad.admin.domain.service.FeatureFlagService;
import com.bhukkad.admin.domain.service.FraudAnalyticsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminOperationsControllerTest {

    @Mock private FraudAnalyticsService fraudAnalyticsService;
    @Mock private AdminQueryService adminQueryService;
    @Mock private ApiKeyService apiKeyService;
    @Mock private FeatureFlagService featureFlagService;
    @InjectMocks private AdminOperationsController controller;

    @Test
    void pendingFraud_returnsPendingActions() {
        when(fraudAnalyticsService.pendingFraud()).thenReturn(List.of(new FraudReviewAction()));

        assertThat(controller.pendingFraud()).hasSize(1);
    }

    @Test
    void scheduleExport_delegatesWithFilters() {
        AnalyticsExportTask task = new AnalyticsExportTask();
        when(fraudAnalyticsService.scheduleExport("orders", "status=COMPLETED")).thenReturn(task);

        assertThat(controller.scheduleExport("orders", "status=COMPLETED")).isSameAs(task);
        verify(fraudAnalyticsService).scheduleExport("orders", "status=COMPLETED");
    }

    @Test
    void scheduleExport_withoutFilters_usesNull() {
        when(fraudAnalyticsService.scheduleExport("orders", null))
                .thenReturn(new AnalyticsExportTask());

        controller.scheduleExport("orders", null);
        verify(fraudAnalyticsService).scheduleExport("orders", null);
    }

    @Test
    void createApiKey_delegatesWithDefaultTtl() {
        ApiKeyService.IssuedKey key = new ApiKeyService.IssuedKey(1L, "bhk-raw", null);
        when(apiKeyService.create("ops", 30)).thenReturn(key);

        // JSON body form (ops console)...
        assertThat(controller.createApiKey(java.util.Map.of("name", "ops"), null, 30)
                .rawKey()).isEqualTo("bhk-raw");
        // ...and the legacy query-param form behave identically.
        assertThat(controller.createApiKey(null, "ops", 30).rawKey()).isEqualTo("bhk-raw");
    }

    @Test
    void validateApiKey_delegates() {
        when(apiKeyService.isValid("bhk-abc")).thenReturn(true);

        assertThat(controller.validateApiKey("bhk-abc")).isTrue();
    }

    @Test
    void setFlag_delegates() {
        controller.setFlag("dark-mode", true);
        verify(featureFlagService).setFlag("dark-mode", true);
    }

    @Test
    void getFlag_delegates() {
        when(featureFlagService.isEnabled("dark-mode")).thenReturn(false);

        assertThat(controller.getFlag("dark-mode")).isFalse();
    }
}
