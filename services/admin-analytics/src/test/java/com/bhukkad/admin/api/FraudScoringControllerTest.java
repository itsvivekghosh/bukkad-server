package com.bhukkad.admin.api;
import com.bhukkad.admin.api.controller.FraudScoringController;

import com.bhukkad.admin.domain.service.FraudRiskScoringService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FraudScoringControllerTest {

    @Mock private FraudRiskScoringService scoringService;
    @InjectMocks private FraudScoringController controller;

    @Test
    void score_returnsScoreWithSeverity() {
        when(scoringService.score(true, false, false, 0)).thenReturn(0.3);
        when(scoringService.severity(0.3)).thenReturn("LOW");

        FraudScoringController.ScoreResponse response = controller.score(true, false, false, 0);

        assertThat(response.score()).isEqualTo(0.3);
        assertThat(response.severity()).isEqualTo("LOW");
    }
}
