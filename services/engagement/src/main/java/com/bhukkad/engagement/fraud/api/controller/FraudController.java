package com.bhukkad.engagement.fraud.api.controller;

import com.bhukkad.engagement.fraud.config.FraudProperties;
import com.bhukkad.engagement.fraud.config.FraudProperties.Configuration;
import com.bhukkad.engagement.fraud.domain.entity.FraudCase;
import com.bhukkad.engagement.fraud.domain.repository.FraudCaseRepository;
import com.bhukkad.engagement.fraud.domain.service.FraudDetectionEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * REST Controller for Anti-Fraud Operations.
 * Provides endpoints for fraud case management, reporting, and configuration.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/fraud")
@Tag(name = "Fraud Management", description = "Anti-fraud detection and case management")
@RequiredArgsConstructor
public class FraudController {

    private final FraudCaseRepository fraudCaseRepository;
    private final FraudDetectionEngine fraudEngine;
    private final FraudProperties fraudProperties;

    /**
     * Get fraud cases by status with pagination.
     */
    @GetMapping("/cases")
    @Operation(summary = "Get fraud cases by status")
    public List<FraudCase> getCasesByStatus(
            @RequestParam(defaultValue = "DETECTED") FraudCase.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        
        List<FraudCase> allCases = fraudCaseRepository.findByStatusOrderByCreatedAtDesc(status);
        
        // Simple pagination
        int from = page * size;
        int to = Math.min(from + size, allCases.size());
        return allCases.subList(from, to);
    }

    /**
     * Get a specific fraud case by ID.
     */
    @GetMapping("/cases/{caseId}")
    @Operation(summary = "Get fraud case details")
    public FraudCase getFraudCase(@PathVariable String caseId) {
        UUID caseUuid = UUID.fromString(caseId);
        return fraudCaseRepository.findByCaseId(caseUuid)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, 
                        "Fraud case not found: " + caseId));
    }

    /**
     * Get fraud statistics summary.
     */
    @GetMapping("/stats")
    @Operation(summary = "Get fraud statistics summary")
    public FraudStats getFraudStats() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        
        return new FraudStats(
                fraudCaseRepository.count(),
                fraudCaseRepository.countByStatus(FraudCase.Status.DETECTED),
                fraudCaseRepository.countByStatus(FraudCase.Status.REVIEWED),
                fraudCaseRepository.countByStatus(FraudCase.Status.CONFIRMED),
                fraudCaseRepository.countByStatus(FraudCase.Status.DISMISSED),
                fraudCaseRepository.countByFraudType(FraudCase.FraudType.FAKE_ORDER_CLAIM),
                fraudCaseRepository.countByFraudType(FraudCase.FraudType.REFUND_SCAM),
                fraudCaseRepository.countByFraudType(FraudCase.FraudType.IDENTITY_SPOOFING),
                fraudCaseRepository.countByFraudType(FraudCase.FraudType.ATTACHMENT_MANIPULATION),
                fraudCaseRepository.countByFraudType(FraudCase.FraudType.BEHAVIORAL_ANOMALY),
                fraudCaseRepository.countNewCasesSince(thirtyDaysAgo),
                fraudCaseRepository.findHighRiskCases(75.0,
                        FraudCase.ConfidenceLevel.HIGH,
                        FraudCase.ConfidenceLevel.CRITICAL).size()
        );
    }

    /**
     * Get fraud configuration.
     */
    @GetMapping("/config")
    @Operation(summary = "Get fraud detection configuration")
    public Configuration getFraudConfig() {
        return new Configuration(fraudProperties);
    }

    /**
     * Mark a fraud case as reviewed.
     */
    @PostMapping("/cases/{caseId}/review")
    @Operation(summary = "Mark fraud case as reviewed")
    public FraudCase reviewCase(@PathVariable String caseId, 
                                 @RequestParam FraudCase.Status newStatus,
                                 @RequestParam String resolutionNotes) {
        UUID caseUuid = UUID.fromString(caseId);
        FraudCase fraudCase = fraudCaseRepository.findByCaseId(caseUuid)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, 
                        "Fraud case not found: " + caseId));

        fraudCase.setStatus(newStatus);
        fraudCase.setResolutionNotes(resolutionNotes);
        fraudCase.setUpdatedAt(LocalDateTime.now());
        
        if (newStatus == FraudCase.Status.ACTION_TAKEN) {
            fraudCase.setResolvedAt(LocalDateTime.now());
        }
        
        return fraudCaseRepository.save(fraudCase);
    }

    /**
     * Get fraud detection engine health status.
     */
    @GetMapping("/health")
    @Operation(summary = "Get fraud detection engine health")
    public FraudHealth getFraudHealth() {
        return new FraudHealth(true, LocalDateTime.now(), "Operational");
    }

    // Response DTOs
    public record FraudStats(
            long totalCases,
            long detectedCases,
            long reviewedCases,
            long confirmedCases,
            long dismissedCases,
            long fakeOrderClaims,
            long refundScams,
            long identitySpoofing,
            long attachmentManipulation,
            long behavioralAnomalies,
            long newCasesLast30Days,
            long highRiskCount
    ) {}

    public record FraudHealth(boolean operational, LocalDateTime timestamp, String message) {}
}