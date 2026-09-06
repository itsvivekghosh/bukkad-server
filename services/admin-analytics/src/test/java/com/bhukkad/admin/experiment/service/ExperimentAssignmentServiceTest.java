package com.bhukkad.admin.experiment.service;

import com.bhukkad.admin.experiment.domain.ExperimentExposure;
import com.bhukkad.admin.experiment.domain.ExperimentExposureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the deterministic A/B assignment contract ported from the
 * monolith ({@code com.bhukkad.experiment.ExperimentAssignmentService}).
 *
 * <p>The bucketing math is a frozen contract: {@code bucket =
 * stableHash(experiment + ":" + userId) mod 10000}, variants consume buckets by
 * cumulative percent weight. Verified bucket pins for {@code checkout-cta-copy}:
 * user 42 → bucket 8628 (treatment), user 7 → bucket 4193 (control).</p>
 */
@ExtendWith(MockitoExtension.class)
class ExperimentAssignmentServiceTest {

    private static final String KEY = "checkout-cta-copy";

    @Mock
    private ExperimentExposureRepository exposureRepository;

    private ExperimentProperties properties;
    private ExperimentAssignmentService service;

    @BeforeEach
    void setUp() {
        properties = new ExperimentProperties();
        ExperimentProperties.Experiment experiment = new ExperimentProperties.Experiment();
        experiment.setVariants(List.of(
                new ExperimentProperties.Variant("control", 50),
                new ExperimentProperties.Variant("treatment", 50)));
        properties.getExperiments().put(KEY, experiment);
        service = new ExperimentAssignmentService(properties, exposureRepository);
    }

    @Test
    void assign_sameUserAndExperiment_returnsSameVariant() {
        stubExposureLogAccepted();

        String first = service.assign(KEY, 42L);
        String second = service.assign(KEY, 42L);

        assertThat(first).isEqualTo(second).isEqualTo("treatment"); // bucket 8628
        assertThat(service.assign(KEY, 7L)).isEqualTo("control");   // bucket 4193
    }

    @Test
    void assign_reachesEveryConfiguredVariant() {
        stubExposureLogAccepted();

        Map<String, Long> counts = new HashMap<>();
        for (long userId = 1; userId <= 2000; userId++) {
            String variant = service.assign(KEY, userId);
            assertThat(variant).isNotNull(); // weights sum to 100 — no remainder
            counts.merge(variant, 1L, Long::sum);
        }

        // 50/50 split over users 1..2000 buckets 1039/961 — sanity bound, not exact.
        assertThat(counts.keySet()).containsExactlyInAnyOrder("control", "treatment");
        assertThat(counts.get("control")).isBetween(900L, 1200L);
        assertThat(counts.get("treatment")).isBetween(900L, 1200L);
    }

    @Test
    void assign_repeatAssignment_persistsExposureOnlyOnce() {
        ExperimentExposure existing = new ExperimentExposure();
        when(exposureRepository.findByExperimentKeyAndUserId(KEY, 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(exposureRepository.save(any(ExperimentExposure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.assign(KEY, 42L);
        service.assign(KEY, 42L);

        verify(exposureRepository, times(1)).save(any(ExperimentExposure.class));
    }

    @Test
    void assign_firstTouch_persistsAuditableExposure() {
        stubExposureLogAccepted();

        service.assign(KEY, 42L);

        ArgumentCaptor<ExperimentExposure> captor = ArgumentCaptor.forClass(ExperimentExposure.class);
        verify(exposureRepository).save(captor.capture());
        ExperimentExposure saved = captor.getValue();
        assertThat(saved.getExperimentKey()).isEqualTo(KEY);
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getVariant()).isEqualTo("treatment");
        assertThat(saved.getBucket()).isEqualTo(8628);
    }

    @Test
    void assign_racedExposureWrite_stillAssigns() {
        when(exposureRepository.findByExperimentKeyAndUserId(anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(exposureRepository.save(any(ExperimentExposure.class)))
                .thenThrow(new DataIntegrityViolationException("uk_experiment_user"));

        assertThat(service.assign(KEY, 42L)).isEqualTo("treatment");
    }

    @Test
    void assign_exposureLogFailure_isBestEffort() {
        when(exposureRepository.findByExperimentKeyAndUserId(anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(exposureRepository.save(any(ExperimentExposure.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThat(service.assign(KEY, 42L)).isEqualTo("treatment");
    }

    @Test
    void assign_experimentsGloballyDisabled_returnsNullWithoutPersistence() {
        properties.setEnabled(false);

        assertThat(service.assign(KEY, 42L)).isNull();
        verifyNoInteractions(exposureRepository);
    }

    @Test
    void assign_unknownExperiment_returnsNull() {
        assertThat(service.assign("nope", 42L)).isNull();
        verifyNoInteractions(exposureRepository);
    }

    @Test
    void assign_disabledExperiment_returnsNull() {
        properties.getExperiment(KEY).setEnabled(false);

        assertThat(service.assign(KEY, 42L)).isNull();
        verifyNoInteractions(exposureRepository);
    }

    @Test
    void assign_nullUserId_returnsNull() {
        assertThat(service.assign(KEY, null)).isNull();
        verifyNoInteractions(exposureRepository);
    }

    @Test
    void assign_weightRemainder_leavesUsersUnassigned() {
        ExperimentProperties.Experiment holdout = new ExperimentProperties.Experiment();
        holdout.setVariants(List.of(new ExperimentProperties.Variant("control", 50)));
        properties.getExperiments().put("holdout-test", holdout);
        stubExposureLogAccepted();

        // Users in buckets 0..4999 get control; the remainder is unassigned.
        assertThat(service.assign("holdout-test", 1L)).isEqualTo("control");
        assertThat(service.assign("holdout-test", 100L)).isNull();
    }

    @Test
    void exposureCounts_aggregatesCohortByVariant() {
        when(exposureRepository.countByVariant(KEY)).thenReturn(List.of(
                new Object[]{"control", 3L},
                new Object[]{"treatment", 5L}));

        Map<String, Long> counts = service.exposureCounts(KEY);

        assertThat(counts).containsEntry("control", 3L).containsEntry("treatment", 5L).hasSize(2);
    }

    @Test
    void exposureCounts_noExposures_returnsEmptyMap() {
        when(exposureRepository.countByVariant(KEY)).thenReturn(List.of());

        assertThat(service.exposureCounts(KEY)).isEmpty();
    }

    private void stubExposureLogAccepted() {
        when(exposureRepository.findByExperimentKeyAndUserId(anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(exposureRepository.save(any(ExperimentExposure.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
