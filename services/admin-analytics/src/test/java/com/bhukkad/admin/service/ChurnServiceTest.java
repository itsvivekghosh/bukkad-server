package com.bhukkad.admin.service;
import com.bhukkad.admin.domain.service.ChurnService;

import com.bhukkad.admin.domain.entity.ChurnScore;
import com.bhukkad.admin.domain.repository.ChurnScoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChurnServiceTest {

    @Mock private ChurnScoreRepository churnScoreRepository;
    @InjectMocks private ChurnService service;

    @Test
    void compute_savesScoreWithModelVersion() {
        when(churnScoreRepository.save(any(ChurnScore.class))).thenAnswer(inv -> inv.getArgument(0));

        ChurnScore score = service.compute(42L, 0.6);

        assertThat(score.getCustomerId()).isEqualTo(42L);
        assertThat(score.getScore()).isEqualTo(0.6);
        assertThat(score.getModelVersion()).isEqualTo("v1");
        assertThat(score.getComputedAt()).isNotNull();
    }

    @Test
    void compute_clampsScoreToRange() {
        when(churnScoreRepository.save(any(ChurnScore.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.compute(1L, 1.8).getScore()).isEqualTo(1.0);
        assertThat(service.compute(1L, -0.5).getScore()).isEqualTo(0.0);
    }

    @Test
    void history_delegatesToRepository() {
        ChurnScore score = new ChurnScore();
        when(churnScoreRepository.findByCustomerId(42L)).thenReturn(List.of(score));

        assertThat(service.history(42L)).containsExactly(score);
        verify(churnScoreRepository).findByCustomerId(42L);
    }

    @Test
    void highRiskCustomers_returnsTop100AtOrAboveRetentionThreshold() {
        List<ChurnScore> cohort = List.of(new ChurnScore());
        when(churnScoreRepository.findTop100ByScoreGreaterThanEqualOrderByScoreDesc(ChurnService.HIGH_RISK_SCORE))
                .thenReturn(cohort);

        assertThat(service.highRiskCustomers()).isSameAs(cohort);
        verify(churnScoreRepository).findTop100ByScoreGreaterThanEqualOrderByScoreDesc(ChurnService.HIGH_RISK_SCORE);
    }

    @Test
    void rescore_touchesLatestScoringRoundForUpsertParity() {
        ChurnScore latest = new ChurnScore();
        latest.setCustomerId(42L);
        latest.setComputedAt(LocalDateTime.of(2026, 8, 1, 3, 0));
        when(churnScoreRepository.findFirstByCustomerIdOrderByComputedAtDesc(42L)).thenReturn(Optional.of(latest));
        when(churnScoreRepository.save(latest)).thenReturn(latest);

        ChurnScore result = service.rescore(42L);

        assertThat(result).isSameAs(latest);
        assertThat(result.getComputedAt()).isAfter(LocalDateTime.of(2026, 8, 1, 3, 0));
    }

    @Test
    void rescore_unknownCustomerReturnsNullLikeMonolith() {
        when(churnScoreRepository.findFirstByCustomerIdOrderByComputedAtDesc(999L)).thenReturn(Optional.empty());

        assertThat(service.rescore(999L)).isNull();
        verify(churnScoreRepository, never()).save(any(ChurnScore.class));
    }
}
