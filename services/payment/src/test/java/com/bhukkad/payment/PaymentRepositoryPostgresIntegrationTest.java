package com.bhukkad.payment;

import com.bhukkad.payment.domain.Payment;
import com.bhukkad.payment.domain.PaymentRepository;
import com.bhukkad.payment.domain.WalletBalance;
import com.bhukkad.payment.domain.WalletBalanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the V2 payment migration and repositories against PostgreSQL.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentRepositoryPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private WalletBalanceRepository walletBalanceRepository;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
        jdbcTemplate.update("DELETE FROM payments");
    }

    @Test
    void migration_appliedBothV1AndV2() {
        Integer outboxTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('outbox_events','idempotency_records')",
                Integer.class);
        assertThat(outboxTables).isEqualTo(2);

        Integer paymentTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema() " +
                        "AND table_name IN ('payments','wallet_balances','wallet_transactions')",
                Integer.class);
        assertThat(paymentTables).isEqualTo(3);
    }

    @Test
    void savePaymentAndWalletBalance() {
        Payment p = new Payment();
        p.setOrderId(1L);
        p.setCustomerId(2L);
        p.setAmount(new BigDecimal("99.99"));
        p.setStatus(Payment.STATUS_SETTLED);
        paymentRepository.saveAndFlush(p);

        WalletBalance wb = new WalletBalance();
        wb.setCustomerId(2L);
        wb.setBalance(new BigDecimal("99.99"));
        walletBalanceRepository.saveAndFlush(wb);

        assertThat(paymentRepository.findByOrderId(1L)).isPresent();
        assertThat(walletBalanceRepository.findByCustomerId(2L)).isPresent();
        assertThat(walletBalanceRepository.findByCustomerId(2L).get().getBalance())
                .isEqualByComparingTo("99.99");
    }

    @Test
    void walletBalance_uniquePerCustomer() {
        WalletBalance wb = new WalletBalance();
        wb.setCustomerId(7L);
        wb.setBalance(BigDecimal.ZERO);
        walletBalanceRepository.saveAndFlush(wb);

        WalletBalance dup = new WalletBalance();
        dup.setCustomerId(7L);
        dup.setBalance(BigDecimal.TEN);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> walletBalanceRepository.saveAndFlush(dup))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
