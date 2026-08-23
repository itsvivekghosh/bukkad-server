package com.bhukkad.experiment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExperimentAssignmentServiceTest {

    @Mock
    private ExperimentExposureRepository exposureRepository;

    private ExperimentAssignmentService service;

    @BeforeEach
    void setUp() {
        ExperimentProperties properties = new ExperimentProperties();
        ExperimentProperties.Experiment experiment = new ExperimentProperties.Experiment();
        experiment.setEnabled(true);
        experiment.setVariants(List.of(
                new ExperimentProperties.Variant("control", 50),
                new ExperimentProperties.Variant("treatment", 50)));
        properties.getExperiments().put("checkout-copy", experiment);
        service = new ExperimentAssignmentService(properties, exposureRepository);
    }

    @Test
    void assign_returnsSameVariantForSameUser() {
        // First call finds no prior exposure; after the save the lookup returns it,
        // mirroring the unique-constraint semantics in production.
        when(exposureRepository.findByExperimentKeyAndUserId(org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExperimentExposure()));
        String first = service.assign("checkout-copy", 42L);
        String second = service.assign("checkout-copy", 42L);
        assertEquals(first, second);
        verify(exposureRepository, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    void assign_splitsUsersAcrossVariants() {
        when(exposureRepository.findByExperimentKeyAndUserId(org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(Optional.empty());
        long control = 0;
        long treatment = 0;
        for (long userId = 1; userId <= 500; userId++) {
            String variant = service.assign("checkout-copy", userId);
            if ("control".equals(variant)) {
                control++;
            } else if ("treatment".equals(variant)) {
                treatment++;
            }
        }
        // Deterministic hashing must populate BOTH arms; neither may be empty.
        assertTrue(control > 0, "control cohort empty");
        assertTrue(treatment > 0, "treatment cohort empty");
    }

    @Test
    void assign_returnsNullWhenExperimentMissingOrDisabled() {
        assertNull(service.assign("unknown-experiment", 1L));

        ExperimentProperties properties = new ExperimentProperties();
        ExperimentProperties.Experiment experiment = new ExperimentProperties.Experiment();
        experiment.setEnabled(false);
        properties.getExperiments().put("off", experiment);
        ExperimentAssignmentService disabled =
                new ExperimentAssignmentService(properties, exposureRepository);
        assertNull(disabled.assign("off", 1L));
    }

    @Test
    void assign_skipsExposureWriteWhenAlreadyLogged() {
        when(exposureRepository.findByExperimentKeyAndUserId(org.mockito.ArgumentMatchers.anyString(), any()))
                .thenReturn(Optional.of(new ExperimentExposure()));
        service.assign("checkout-copy", 7L);
        verify(exposureRepository, never()).save(any());
    }

    @Test
    void exposureCounts_groupsByVariant() {
        when(exposureRepository.countByVariant("checkout-copy"))
                .thenReturn(List.of(new Object[]{"control", 12L}, new Object[]{"treatment", 30L}));
        Map<String, Long> counts = service.exposureCounts("checkout-copy");
        assertEquals(12L, counts.get("control"));
        assertEquals(30L, counts.get("treatment"));
    }
}
