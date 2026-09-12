package com.bhukkad.payment;

import com.bhukkad.payment.domain.entity.Payment;
import com.bhukkad.payment.domain.entity.CommissionTier;
import com.bhukkad.payment.domain.entity.DunningRun;
import com.bhukkad.payment.domain.repository.PaymentRepository;
import com.bhukkad.payment.domain.repository.CommissionTierRepository;
import com.bhukkad.payment.domain.repository.DunningRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates Batch 3 payment depth (commission tiers, dunning) against PG.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommissionDunningPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CommissionTierRepository tierRepository;
    @Autowired private DunningRunRepository dunningRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM dunning_runs");
        jdbcTemplate.update("DELETE FROM commission_tiers");
        jdbcTemplate.update("DELETE FROM disputes");
        jdbcTemplate.update("DELETE FROM restaurant_settlements");
        jdbcTemplate.update("DELETE FROM settlement_runs");
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Test
    void migration_appliedV5() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('commission_tiers','dunning_runs')", Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void commissionTiersOrderByMinCount() {
        tierRepository.saveAndFlush(tier(50, 100, "10.00"));
        tierRepository.saveAndFlush(tier(0, 49, "20.00"));
        tierRepository.saveAndFlush(tier(101, null, "5.00"));

        assertThat(tierRepository.findByActiveTrueOrderByMinOrderCountAsc())
                .extracting(CommissionTier::getMinOrderCount)
                .containsExactly(0, 50, 101);
    }

    @Test
    void dunningRunUniquePerPaymentAttempt() {
        Long paymentId = jdbcTemplate.queryForObject(
                "INSERT INTO payments (order_id, customer_id, amount, currency, status, provider, created_at, updated_at) " +
                        "VALUES (1, 1, 100.00, 'INR', 'FAILED', 'sim', now(), now()) RETURNING id",
                Long.class);

        DunningRun run = new DunningRun();
        run.setPaymentId(paymentId);
        run.setAttempt(1);
        run.setStatus("SCHEDULED");
        run.setScheduledAt(java.time.LocalDateTime.now());
        dunningRepository.saveAndFlush(run);

        DunningRun dup = new DunningRun();
        dup.setPaymentId(paymentId);
        dup.setAttempt(1);
        dup.setStatus("SCHEDULED");
        dup.setScheduledAt(java.time.LocalDateTime.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dunningRepository.saveAndFlush(dup))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private CommissionTier tier(int min, Integer max, String pct) {
        CommissionTier t = new CommissionTier();
        t.setMinOrderCount(min);
        t.setMaxOrderCount(max);
        t.setCommissionPct(new BigDecimal(pct));
        t.setActive(true);
        return t;
    }
}
