package com.bhukkad.restaurant.api.controller;

import com.bhukkad.restaurant.domain.service.impl.ExperimentAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Security audit for {@link ExperimentAdminController}.
 */
@ExtendWith(MockitoExtension.class)
class ExperimentAdminControllerSecurityTest {

    @Mock
    private ExperimentAssignmentService assignmentService;

    @InjectMocks
    private ExperimentAdminController controller;

    @Test
    void exposures_requiresAdminRole() {
        // Auth is enforced at the class level.
        assertThat(ExperimentAdminController.class.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class)).isNotNull();
        assertThat(ExperimentAdminController.class.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value()).contains("ADMIN");
    }

    @Test
    void exposures_returnsCensus() {
        when(assignmentService.exposureCounts("exp-1"))
                .thenReturn(Map.of("control", 10L, "treatment", 15L));

        ResponseEntity<Map<String, Long>> response = controller.exposures("exp-1");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("control", 10L).containsEntry("treatment", 15L);
    }

    @Test
    void exposures_unknownExperiment_returnsEmptyMap() {
        when(assignmentService.exposureCounts("unknown")).thenReturn(Map.of());

        ResponseEntity<Map<String, Long>> response = controller.exposures("unknown");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }
}
