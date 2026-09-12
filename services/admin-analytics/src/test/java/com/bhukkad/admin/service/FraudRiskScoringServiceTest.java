package com.bhukkad.admin.service;
import com.bhukkad.admin.domain.service.FraudRiskScoringService;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRiskScoringServiceTest {

    private final FraudRiskScoringService service = new FraudRiskScoringService();

    @Test
    void score_zeroFlags_isLow() {
        double score = service.score(false, false, false, 0);
        assertThat(score).isEqualTo(0.0);
        assertThat(service.severity(score)).isEqualTo("LOW");
    }

    @Test
    void score_highFrequencyAndSuspicious_isHigh() {
        double score = service.score(true, true, true, 3);
        assertThat(score).isEqualTo(1.0);
        assertThat(service.severity(score)).isEqualTo("HIGH");
    }

    @Test
    void score_singleFlag_isLowToMedium() {
        assertThat(service.severity(service.score(true, false, false, 0))).isEqualTo("LOW");
    }

    @Test
    void score_medium_flags() {
        double score = service.score(false, false, true, 2);
        assertThat(service.severity(score)).isEqualTo("MEDIUM");
    }

    @Test
    void score_isCappedAtOne() {
        assertThat(service.score(true, true, true, 10)).isEqualTo(1.0);
    }
}
