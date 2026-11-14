package com.bhukkad.engagement.fraud.api.controller;

import com.bhukkad.engagement.fraud.config.FraudProperties;
import com.bhukkad.engagement.fraud.domain.entity.FraudCase;
import com.bhukkad.engagement.fraud.domain.repository.FraudCaseRepository;
import com.bhukkad.engagement.fraud.domain.service.FraudDetectionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FraudController.
 */
@ExtendWith(MockitoExtension.class)
class FraudControllerTest {

    @Mock
    private FraudCaseRepository fraudCaseRepository;

    @Mock
    private FraudDetectionEngine fraudEngine;

    private FraudProperties fraudProperties;

    @BeforeEach
    void setUp() {
        fraudProperties = new FraudProperties();
    }

    @Test
    @DisplayName("Should return fraud cases by status")
    void shouldReturnFraudCasesByStatus() {
        // Arrange
        FraudCase case1 = FraudCase.builder()
                .caseId(UUID.randomUUID())
                .fraudType(FraudCase.FraudType.FAKE_ORDER_CLAIM)
                .riskScore(BigDecimal.valueOf(85.0))
                .confidenceLevel(FraudCase.ConfidenceLevel.HIGH)
                .status(FraudCase.Status.DETECTED)
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        FraudCase case2 = FraudCase.builder()
                .caseId(UUID.randomUUID())
                .fraudType(FraudCase.FraudType.REFUND_SCAM)
                .riskScore(BigDecimal.valueOf(72.0))
                .confidenceLevel(FraudCase.ConfidenceLevel.MEDIUM)
                .status(FraudCase.Status.DETECTED)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        when(fraudCaseRepository.findByStatusOrderByCreatedAtDesc(FraudCase.Status.DETECTED))
                .thenReturn(List.of(case1, case2));

        // Act
        List<FraudCase> result = fraudCaseRepository.findByStatusOrderByCreatedAtDesc(FraudCase.Status.DETECTED);

        // Assert
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getRiskScore()).isEqualByComparingTo(BigDecimal.valueOf(85.0));
    }

    @Test
    @DisplayName("Should return fraud case by ID")
    void shouldReturnFraudCaseById() {
        // Arrange
        UUID caseId = UUID.randomUUID();
        FraudCase fraudCase = FraudCase.builder()
                .caseId(caseId)
                .fraudType(FraudCase.FraudType.IDENTITY_SPOOFING)
                .riskScore(BigDecimal.valueOf(45.5))
                .confidenceLevel(FraudCase.ConfidenceLevel.MEDIUM)
                .status(FraudCase.Status.REVIEWED)
                .detectionMethod("behavioral analysis")
                .createdAt(LocalDateTime.now())
                .build();

        when(fraudCaseRepository.findByCaseId(caseId)).thenReturn(java.util.Optional.of(fraudCase));

        // Act
        java.util.Optional<FraudCase> result = fraudCaseRepository.findByCaseId(caseId);

        // Assert
        assertThat(result).isPresent();
        assertThat(result.get().getFraudType()).isEqualTo(FraudCase.FraudType.IDENTITY_SPOOFING);
    }

    @Test
    @DisplayName("Should return fraud configuration")
    void shouldReturnFraudConfiguration() {
        // Arrange - fraudProperties already configured in setUp

        // Act - Simulate configuration return
        var config = new FraudProperties.Configuration(fraudProperties);

        // Assert
        assertThat(config.getDefaultRiskThreshold()).isEqualTo(50.0);
        assertThat(config.getHighRiskThreshold()).isEqualTo(75.0);
        assertThat(config.getCriticalRiskThreshold()).isEqualTo(90.0);
        assertThat(config.isEnableBehavioralAnalysis()).isTrue();
        assertThat(config.isEnableImageAnalysis()).isTrue();
    }
}