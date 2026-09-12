package com.bhukkad.admin.api.controller;

import com.bhukkad.admin.domain.service.FraudRiskScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Fraud scoring + dashboard (port of monolith {@code FraudDashboardController}).
 */
@RestController
@RequestMapping("/api/v1/admin/fraud")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class FraudScoringController {

    private final FraudRiskScoringService scoringService;

    @GetMapping("/score")
    public ScoreResponse score(@RequestParam boolean highFrequencyOrders,
                               @RequestParam boolean suspiciousAddress,
                               @RequestParam boolean fastRefundHistory,
                               @RequestParam(defaultValue = "0") int recentDisputes) {
        double score = scoringService.score(highFrequencyOrders, suspiciousAddress, fastRefundHistory, recentDisputes);
        return new ScoreResponse(score, scoringService.severity(score));
    }

    public record ScoreResponse(double score, String severity) {
    }
}