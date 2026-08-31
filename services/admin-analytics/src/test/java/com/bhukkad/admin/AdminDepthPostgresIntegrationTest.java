package com.bhukkad.admin;

import com.bhukkad.admin.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Batch E depth tables (churn, experiment, data export) on PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminDepthPostgresIntegrationTest extends AbstractAdminPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChurnScoreRepository churnRepository;
    @Autowired private ExperimentExposureRepository experimentRepository;
    @Autowired private DataExportRequestRepository exportRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM data_export_requests");
        jdbcTemplate.update("DELETE FROM experiment_exposures");
        jdbcTemplate.update("DELETE FROM churn_scores");
    }

    @Test
    void migration_appliedV3Tables() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('churn_scores','experiment_exposures','data_export_requests')",
                Integer.class);
        assertThat(tables).isEqualTo(3);
    }

    @Test
    void churnScorePersists() {
        ChurnScore score = new ChurnScore();
        score.setCustomerId(1L);
        score.setScore(0.75);
        score.setModelVersion("v1");
        score.setComputedAt(LocalDateTime.now());
        churnRepository.saveAndFlush(score);

        assertThat(churnRepository.findByCustomerId(1L)).hasSize(1);
    }

    @Test
    void experimentExposureAssignOnce() {
        ExperimentExposure e = new ExperimentExposure();
        e.setCustomerId(7L);
        e.setExperiment("checkout-v2");
        e.setVariant("B");
        experimentRepository.saveAndFlush(e);

        assertThat(experimentRepository.findByCustomerIdAndExperiment(7L, "checkout-v2")).isPresent();
        assertThat(experimentRepository.findByCustomerId(7L)).hasSize(1);
    }

    @Test
    void dataExportLifecycle() {
        DataExportRequest request = new DataExportRequest();
        request.setCustomerId(3L);
        request.setFormat("CSV");
        request.setStatus(DataExportRequest.STATUS_PENDING);
        exportRepository.saveAndFlush(request);

        request.setStatus(DataExportRequest.STATUS_COMPLETED);
        request.setFileUrl("/exports/x");
        exportRepository.saveAndFlush(request);

        assertThat(exportRepository.findByCustomerId(3L)).hasSize(1);
        assertThat(exportRepository.findByCustomerId(3L).get(0).getStatus())
                .isEqualTo(DataExportRequest.STATUS_COMPLETED);
    }

    @Test
    void experimentService_assignmentDeterministic() {
        com.bhukkad.admin.service.ExperimentService service =
                new com.bhukkad.admin.service.ExperimentService(experimentRepository);
        var first = service.assign(10L, "exp-1", List.of("A", "B"));
        var second = service.assign(10L, "exp-1", List.of("A", "B"));
        assertThat(first.getId()).isEqualTo(second.getId());
    }
}
