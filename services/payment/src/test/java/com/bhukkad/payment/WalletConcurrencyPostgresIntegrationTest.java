package com.bhukkad.payment;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.payment.domain.WalletTransaction;
import com.bhukkad.payment.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-01 finish money-safety acceptance on real PostgreSQL (migrations V1-V8):
 * the pessimistic FOR UPDATE path stays the adopted serialization (docs
 * §3.7) — heavy concurrent credit/debit must end at an exact,
 * never-negative balance, the new @Version column must not deadlock or
 * reject legitimately-locked writes, and the CHECK/UNIQUE guards must exist.
 * NOT_SUPPORTED runs each worker in its own committed transaction (the
 * default @DataJpaTest rollback tx would be invisible across threads).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(WalletService.class)
class WalletConcurrencyPostgresIntegrationTest extends AbstractPaymentPostgresTest {

    private static final long CUSTOMER = 4242L;

    @Autowired private WalletService walletService;
    @Autowired private com.bhukkad.payment.domain.WalletBalanceRepository balanceRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM wallet_transactions");
        jdbcTemplate.update("DELETE FROM wallet_balances");
    }

    private BigDecimal balance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM wallet_balances WHERE customer_id = " + CUSTOMER,
                BigDecimal.class);
    }

    private static final int THREADS = 16;
    private static final int OPS_PER_THREAD = 20;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDebits_neverOverdraw_andFinalBalanceIsExact() throws Exception {
        walletService.credit(CUSTOMER, new BigDecimal("100.00"), "seed");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        CopyOnWriteArrayList<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    walletService.debit(CUSTOMER, new BigDecimal("10.00"), "race");
                    succeeded.incrementAndGet();
                } catch (BusinessException expected) {
                    insufficient.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable other) {
                    unexpected.add(other);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty(); // no lock exceptions, no negative-balance CHECK fire
        // 16 debit attempts of 10.00 vs 100.00 funded → exactly 10 may succeed.
        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(insufficient.get()).isEqualTo(6);
        assertThat(balance()).isZero();
        // Ledger agrees with the row: every successful debit recorded 10.00.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wallet_transactions WHERE type = 'DEBIT'", Integer.class))
                .isEqualTo(10);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCredits_accumulateExactly_underVersionAndRowLock() throws Exception {
        // Seed at zero directly through the repository: credit(ZERO) is a
        // domain error by design, so create the row without the service.
        com.bhukkad.payment.domain.WalletBalance seed = new com.bhukkad.payment.domain.WalletBalance();
        seed.setCustomerId(CUSTOMER);
        seed.setBalance(BigDecimal.ZERO);
        seed.setUpdatedAt(java.time.LocalDateTime.now());
        balanceRepository.saveAndFlush(seed);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        CopyOnWriteArrayList<Throwable> unexpected = new CopyOnWriteArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                try {
                    go.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        walletService.credit(CUSTOMER, BigDecimal.ONE, "storm");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } catch (Throwable other) {
                    unexpected.add(other);
                }
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        assertThat(unexpected).isEmpty();
        assertThat(balance()).isEqualByComparingTo(new BigDecimal(THREADS * OPS_PER_THREAD));
        // @Version bumped once per locked update, never rejected the path.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM wallet_balances WHERE customer_id = " + CUSTOMER, Long.class))
                .isEqualTo((long) THREADS * OPS_PER_THREAD);
        List<String> recent = jdbcTemplate.query(
                "SELECT type FROM wallet_transactions ORDER BY id DESC LIMIT 5",
                (rs, n) -> rs.getString("type"));
        assertThat(recent).hasSize(5);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void v8Migration_addsVersionColumnCheckAndUniqueGuards() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_name = 'wallet_balances' AND column_name = 'version'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_wallet_balance_nonneg'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'uk_wallet_customer'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void databaseRejectsNegativeBalance_evenBypassingTheService() {
        walletService.credit(CUSTOMER, new BigDecimal("5.00"), "seed");
        try {
            jdbcTemplate.update("UPDATE wallet_balances SET balance = -1 WHERE customer_id = " + CUSTOMER);
            throw new AssertionError("CHECK ck_wallet_balance_nonneg should have rejected -1");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected).hasMessageContaining("ck_wallet_balance_nonneg");
        }
    }
}
