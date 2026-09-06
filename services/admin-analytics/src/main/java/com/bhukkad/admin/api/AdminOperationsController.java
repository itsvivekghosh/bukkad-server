package com.bhukkad.admin.api;

import com.bhukkad.admin.domain.AnalyticsExportTask;
import com.bhukkad.admin.domain.FraudReviewAction;
import com.bhukkad.admin.service.AdminQueryService;
import com.bhukkad.admin.service.ApiKeyService;
import com.bhukkad.admin.service.FeatureFlagService;
import com.bhukkad.admin.service.FraudAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Admin operations surface (port of monolith {@code AdminController} +
 * {@code FraudDashboardController} + {@code AnalyticsExportController} +
 * {@code ApiKeyAdminController} + {@code FeatureFlagController}).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminOperationsController {

    private final FraudAnalyticsService fraudAnalyticsService;
    private final AdminQueryService adminQueryService;
    private final ApiKeyService apiKeyService;
    private final FeatureFlagService featureFlagService;

    @GetMapping("/fraud/pending")
    public List<FraudReviewAction> pendingFraud() {
        return fraudAnalyticsService.pendingFraud();
    }

    @PostMapping("/exports")
    public AnalyticsExportTask scheduleExport(@RequestParam String exportType,
                                              @RequestParam(required = false) String filters) {
        return fraudAnalyticsService.scheduleExport(exportType, filters);
    }

    @PostMapping("/api-keys")
    public ApiKeyService.IssuedKey createApiKey(@RequestParam String name,
                                                @RequestParam(defaultValue = "30") int ttlDays) {
        return apiKeyService.create(name, ttlDays);
    }

    @GetMapping("/api-keys/validate")
    public boolean validateApiKey(@RequestHeader("X-Api-Key") String rawKey) {
        return apiKeyService.isValid(rawKey);
    }

    @PutMapping("/flags/{name}")
    public Object setFlag(@PathVariable String name, @RequestParam boolean enabled) {
        featureFlagService.setFlag(name, enabled);
        return java.util.Map.of("flag", name, "enabled", enabled);
    }

    @GetMapping("/flags/{name}")
    public boolean getFlag(@PathVariable String name) {
        return featureFlagService.isEnabled(name);
    }
}