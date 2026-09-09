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
 * Validates Priority 8 admin depth (fraud review queue, analytics export).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FraudAnalyticsPostgresIntegrationTest extends AbstractAdminPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private FraudReviewActionRepository fraudRepository;
    @Autowired private AnalyticsExportTaskRepository exportRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM analytics_export_tasks");
        jdbcTemplate.update("DELETE FROM fraud_review_queue");
        jdbcTemplate.update("DELETE FROM data_export_requests");
        jdbcTemplate.update("DELETE FROM experiment_exposures");
        jdbcTemplate.update("DELETE FROM churn_scores");
    }

    @Test
    void migration_appliedV4() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('fraud_review_queue','analytics_export_tasks')",
                Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void fraudReviewQueuePersists() {
        FraudReviewAction action = new FraudReviewAction();
        action.setCustomerId(1L);
        action.setRule("velocity");
        action.setSeverity("HIGH");
        action.setStatus(FraudReviewAction.STATUS_PENDING);
        action.setCreatedAt(LocalDateTime.now());
        fraudRepository.saveAndFlush(action);

        assertThat(fraudRepository.findByStatus(FraudReviewAction.STATUS_PENDING)).hasSize(1);
    }

    @Test
    void analyticsExportTaskPersists() {
        AnalyticsExportTask task = new AnalyticsExportTask();
        task.setExportType("ORDERS");
        task.setStatus(AnalyticsExportTask.STATUS_PENDING);
        task.setCreatedAt(LocalDateTime.now());
        exportRepository.saveAndFlush(task);

        assertThat(exportRepository.findByStatus(AnalyticsExportTask.STATUS_PENDING)).hasSize(1);
    }
}
