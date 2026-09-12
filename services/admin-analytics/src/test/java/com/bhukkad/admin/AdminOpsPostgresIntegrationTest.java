package com.bhukkad.admin;
import com.bhukkad.admin.domain.entity.ApiKey;
import com.bhukkad.admin.domain.repository.ApiKeyRepository;

import com.bhukkad.admin.domain.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Batch 5 admin depth (api keys, feature flags) against PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminOpsPostgresIntegrationTest extends AbstractAdminPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApiKeyRepository apiKeyRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM api_keys");
        jdbcTemplate.update("DELETE FROM feature_flags");
        jdbcTemplate.update("DELETE FROM analytics_export_tasks");
        jdbcTemplate.update("DELETE FROM fraud_review_queue");
        jdbcTemplate.update("DELETE FROM data_export_requests");
        jdbcTemplate.update("DELETE FROM experiment_exposures");
        jdbcTemplate.update("DELETE FROM churn_scores");
    }

    @Test
    void migration_appliedV5() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('api_keys','feature_flags')", Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void apiKeyIssueAndValidate() {
        ApiKeyService service = new ApiKeyService(apiKeyRepository);
        ApiKeyService.IssuedKey issued = service.create("ops-bot", 30);

        assertThat(issued.rawKey()).startsWith("bhk-");
        assertThat(service.isValid(issued.rawKey())).isTrue();
        assertThat(service.isValid("bhk-forged")).isFalse();
    }

    @Test
    void apiKeyUniqueByHash() {
        ApiKey key = new ApiKey();
        key.setKeyHash("hash-abc");
        key.setName("k1");
        key.setStatus(ApiKey.STATUS_ACTIVE);
        key.setExpiresAt(LocalDateTime.now().plusDays(1));
        apiKeyRepository.saveAndFlush(key);

        assertThat(apiKeyRepository.findByKeyHashAndStatus("hash-abc", ApiKey.STATUS_ACTIVE)).isPresent();
    }
}
