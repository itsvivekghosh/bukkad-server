package com.bhukkad.payment;

import com.bhukkad.payment.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Priority 3 payment depth (disputes) against PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DisputePostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DisputeRepository disputeRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM disputes");
        jdbcTemplate.update("DELETE FROM restaurant_settlements");
        jdbcTemplate.update("DELETE FROM settlement_runs");
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Test
    void migration_appliedV4() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('disputes')", Integer.class);
        assertThat(tables).isEqualTo(1);
    }

    @Test
    void disputePersistsAndQueriesByStatus() {
        // Dispute has a FK to payments — create the parent row first.
        Long paymentId = jdbcTemplate.queryForObject(
                "INSERT INTO payments (order_id, customer_id, amount, currency, status, provider, created_at, updated_at) " +
                        "VALUES (10, 7, 240.00, 'INR', 'SETTLED', 'sim', now(), now()) RETURNING id",
                Long.class);

        Dispute dispute = new Dispute();
        dispute.setPaymentId(paymentId);
        dispute.setCustomerId(7L);
        dispute.setOrderId(10L);
        dispute.setReason("Wrong item");
        dispute.setStatus(Dispute.STATUS_OPEN);
        dispute.setAmount(new BigDecimal("240.00"));
        dispute.setCreatedAt(LocalDateTime.now());
        disputeRepository.saveAndFlush(dispute);

        assertThat(disputeRepository.findByCustomerId(7L)).hasSize(1);
        assertThat(disputeRepository.findByStatus(Dispute.STATUS_OPEN)).hasSize(1);
    }
}
