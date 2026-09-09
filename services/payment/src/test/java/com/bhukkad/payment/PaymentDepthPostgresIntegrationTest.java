package com.bhukkad.payment;

import com.bhukkad.payment.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentDepthPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SettlementRunRepository runRepository;
    @Autowired private RestaurantSettlementRepository settlementRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM restaurant_settlements");
        jdbcTemplate.update("DELETE FROM settlement_runs");
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Test
    void migration_appliedV3() {
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('settlement_runs','restaurant_settlements')",
                Integer.class);
        assertThat(tables).isEqualTo(2);
    }

    @Test
    void settlementRunPersists() {
        SettlementRun run = new SettlementRun();
        run.setRunDate(LocalDate.now());
        run.setStatus("RUNNING");
        runRepository.saveAndFlush(run);

        RestaurantSettlement stl = new RestaurantSettlement();
        stl.setSettlementRunId(run.getId());
        stl.setRestaurantId(5L);
        stl.setOrderCount(10);
        stl.setGrossAmount(new BigDecimal("1000.00"));
        stl.setCommission(new BigDecimal("200.00"));
        stl.setNetAmount(new BigDecimal("800.00"));
        stl.setStatus("PENDING");
        settlementRepository.saveAndFlush(stl);

        assertThat(settlementRepository.findByRestaurantId(5L)).hasSize(1);
        assertThat(settlementRepository.findByRestaurantId(5L).get(0).getNetAmount())
                .isEqualByComparingTo("800.00");
    }
}