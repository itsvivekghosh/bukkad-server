package com.bhukkad.admin;

import com.bhukkad.admin.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Batch E depth tables (churn, experiment, data export) on PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminDepthPostgresIntegrationTest extends AbstractAdminPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ChurnScoreRepository churnRepository;
    @Autowired private com.bhukkad.admin.experiment.domain.ExperimentExposureRepository experimentRepository;
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
    void experimentExposureMonolithSchemaPersists() {
        com.bhukkad.admin.experiment.domain.ExperimentExposure exposure =
                new com.bhukkad.admin.experiment.domain.ExperimentExposure();
        exposure.setExperimentKey("checkout-cta-copy");
        exposure.setUserId(7L);
        exposure.setVariant("treatment");
        exposure.setBucket(8628);
        experimentRepository.saveAndFlush(exposure);

        assertThat(experimentRepository.findByExperimentKeyAndUserId("checkout-cta-copy", 7L)).isPresent();
        assertThat(exposure.getExposedAt()).isNotNull();
        assertThat(experimentRepository.countByVariant("checkout-cta-copy"))
                .hasSize(1)
                .satisfies(rows -> {
                    Object[] top = rows.get(0);
                    assertThat(top[0]).isEqualTo("treatment");
                    assertThat(top[1]).isEqualTo(1L);
                });
    }

    @Test
    void experimentExposure_uniquePerExperimentAndUser() {
        com.bhukkad.admin.experiment.domain.ExperimentExposure first =
                new com.bhukkad.admin.experiment.domain.ExperimentExposure();
        first.setExperimentKey("checkout-cta-copy");
        first.setUserId(9L);
        first.setVariant("control");
        first.setBucket(4193);
        experimentRepository.saveAndFlush(first);

        com.bhukkad.admin.experiment.domain.ExperimentExposure duplicate =
                new com.bhukkad.admin.experiment.domain.ExperimentExposure();
        duplicate.setExperimentKey("checkout-cta-copy");
        duplicate.setUserId(9L);
        duplicate.setVariant("treatment");
        duplicate.setBucket(8628);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> experimentRepository.saveAndFlush(duplicate));
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
}
