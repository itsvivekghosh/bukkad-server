package com.bhukkad.admin.service;

import com.bhukkad.admin.domain.ChurnScore;
import com.bhukkad.admin.domain.ChurnScoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
