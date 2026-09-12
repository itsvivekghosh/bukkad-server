package com.bhukkad.admin.domain.event;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRiskAssessmentTest {

    @Test
    void noScore_isTheDisabledSentinel() {
        FraudRiskAssessment assessment = FraudRiskAssessment.noScore();

        assertThat(assessment.scored()).isFalse();
        assertThat(assessment.score()).isZero();
        assertThat(assessment.level()).isEqualTo(FraudRiskAssessment.Level.LOW);
        assertThat(assessment.reasons()).isEmpty();
    }

    @Test
    void recordAndLevels_exposeValues() {
        FraudRiskAssessment assessment =
                new FraudRiskAssessment(true, 90, FraudRiskAssessment.Level.CRITICAL, List.of("velocity"));

        assertThat(assessment.reasons()).containsExactly("velocity");
        assertThat(assessment).isEqualTo(new FraudRiskAssessment(
                true, 90, FraudRiskAssessment.Level.CRITICAL, List.of("velocity")));
        assertThat(FraudRiskAssessment.Level.values()).containsExactly(
                FraudRiskAssessment.Level.LOW,
                FraudRiskAssessment.Level.MEDIUM,
                FraudRiskAssessment.Level.HIGH,
                FraudRiskAssessment.Level.CRITICAL);
        assertThat(FraudRiskAssessment.Level.valueOf("HIGH")).isEqualTo(FraudRiskAssessment.Level.HIGH);
        assertThat(assessment.toString()).contains("CRITICAL");
    }

    @Test
    void fraudBlockedException_carriesAuditMetadata() {
        FraudBlockedException exception =
                new FraudBlockedException("unusual activity", "AUTH_LOGIN", 42L);

        assertThat(exception).isInstanceOf(RuntimeException.class)
                .hasMessage("unusual activity");
        assertThat(exception.getEventType()).isEqualTo("AUTH_LOGIN");
        assertThat(exception.getRetryAfterSeconds()).isEqualTo(42L);
    }
}
