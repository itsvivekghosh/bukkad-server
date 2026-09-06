package com.bhukkad.admin.experiment.api;

import com.bhukkad.admin.experiment.service.ExperimentAssignmentService;
import com.bhukkad.common.dto.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the experiment admin surface ported from the monolith
 * ({@code com.bhukkad.experiment.ExperimentAdminController}).
 *
 * <p>Contract parity: GET {@code /api/v1/admin/experiments/{experimentKey}/exposures}
 * returning the ApiResponse envelope ({@code success}/{@code data}/…). Authz
 * follows the service convention — no method-level security annotations;
 * SecurityConfig requires authentication for every route and the gateway
 * fronts the admin surface.</p>
 */
@ExtendWith(MockitoExtension.class)
class ExperimentAdminControllerTest {

    @Mock
    private ExperimentAssignmentService assignmentService;

    @InjectMocks
    private ExperimentAdminController controller;

    @Test
    void exposures_returnsCohortCensusInEnvelope() {
        when(assignmentService.exposureCounts("checkout-cta-copy"))
                .thenReturn(Map.of("control", 3L, "treatment", 5L));

        ResponseEntity<ApiResponse<Map<String, Long>>> response =
                controller.exposures("checkout-cta-copy");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData())
                .containsEntry("control", 3L)
                .containsEntry("treatment", 5L);
        verify(assignmentService).exposureCounts("checkout-cta-copy");
    }

    @Test
    void exposures_unknownExperiment_returnsEmptyCensus() {
        when(assignmentService.exposureCounts("nope")).thenReturn(Map.of());

        ResponseEntity<ApiResponse<Map<String, Long>>> response = controller.exposures("nope");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEmpty();
    }

    @Test
    void exposures_preservesMonolithPathContract() throws Exception {
        RequestMapping classMapping = ExperimentAdminController.class.getAnnotation(RequestMapping.class);
        assertThat(classMapping).isNotNull();
        assertThat(classMapping.value()).containsExactly("/api/v1/admin/experiments");

        Method exposures = ExperimentAdminController.class.getMethod("exposures", String.class);
        GetMapping getMapping = exposures.getAnnotation(GetMapping.class);
        assertThat(getMapping).isNotNull();
        assertThat(getMapping.value()).containsExactly("/{experimentKey}/exposures");

        PathVariable pathVariable = exposures.getParameters()[0].getAnnotation(PathVariable.class);
        assertThat(pathVariable).isNotNull();
        assertThat(pathVariable.value()).isEqualTo("experimentKey");
    }

    @Test
    void exposures_carryClassLevelAdminGate() {
        // Security convention (post-audit): the experiment admin surface is
        // gated by a class-level @PreAuthorize("hasRole('ADMIN')").
        assertThat(ExperimentAdminController.class.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class)).isNotNull();
    }
}
