package com.bhukkad.growth.domain.service;

import com.bhukkad.growth.AbstractGrowthPostgresTest;
import com.bhukkad.growth.api.LoyaltyDailyCapExceededException;
import com.bhukkad.growth.domain.repository.LoyaltyPointBalanceRepository;
import com.bhukkad.growth.domain.repository.LoyaltyPointsLedgerRepository;
import com.bhukkad.growth.domain.service.LoyaltyService;
import com.bhukkad.growth.domain.service.LoyaltyCreditService;
import com.bhukkad.growth.domain.service.LoyaltyReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-first concurrency contract for the durable loyalty ledger (ADR-005 /
 * audit feature #4) against real PostgreSQL:
 *
 * <ol>
 *   <li>N concurrent redemptions of the same balance → never negative,
 *       exactly the affordable count succeed (conditional single-statement
 *       decrement, no TOCTOU);</li>
 *   <li>concurrent credit + redeem → no lost update: final balance always
 *       equals credits minus successful debits (atomic upsert + conditional
 *       decrement, never a Java read-modify-write);</li>
 *   <li>an idempotency key credits exactly once;</li>
 *   <li>the daily credit cap rejects over-cap grants (422);</li>
 *   <li>the reconciliation job snaps drifted balances back to the ledger sum.</li>
 * </ol>
 */
@SpringBootTest
class LoyaltyLedgerConcurrencyPostgresTest extends AbstractGrowthPostgresTest {

    private static final int MIN_REDEMPTION = 100;

    @Autowired
    private LoyaltyService loyaltyService;
    @Autowired
    private LoyaltyCreditService loyaltyCreditService;
    @Autowired
    private LoyaltyReconciliationService reconciliationService;
    @Autowired
    private LoyaltyPointsLedgerRepository ledgerRepository;
    @Autowired
    private LoyaltyPointBalanceRepository balanceRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM loyalty_points_ledger");
        jdbcTemplate.update("DELETE FROM loyalty_point_balances");
        jdbcTemplate.update("DELETE FROM idempotency_records");
    }

    @Test
    void concurrentRedeems_neverOverspend_exactlyAffordableCountSucceed() throws Exception {
        long customerId = 1001L;
        loyaltyService.creditPoints(customerId, 1_000, "SEED", null);

        int threads = 20;
        AtomicInteger successes = new AtomicInteger();
        runConcurrently(threads, i -> {
            if (loyaltyService.redeemPoints(customerId, MIN_REDEMPTION)) {
                successes.incrementAndGet();
            }
        });

        assertThat(successes.get()).isEqualTo(10);
        assertThat(balancePoints(customerId)).isEqualTo(0);
        assertThat(ledgerRepository.ledgerBalance(customerId)).isEqualTo(0);
    }

    @Test
    void concurrentCreditAndRedeem_noLostUpdate_balanceMatchesLedger() throws Exception {
        long customerId = 1002L;
        int creditThreads = 10;
        int redeemThreads = 10;

        // Seed a starting balance so early redemptions have something to spend.
        loyaltyService.creditPoints(customerId, MIN_REDEMPTION, "SEED", null);

        AtomicInteger successfulRedeems = new AtomicInteger();
        runConcurrently(creditThreads + redeemThreads, i -> {
            if (i < creditThreads) {
                loyaltyService.creditPoints(customerId, 100, "ORDER_REWARD", null);
            } else if (loyaltyService.redeemPoints(customerId, 50)) {
                successfulRedeems.incrementAndGet();
            }
        });

        long expected = MIN_REDEMPTION + (creditThreads * 100L) - (successfulRedeems.get() * 50L);
        long ledgerSum = ledgerRepository.ledgerBalance(customerId);
        assertThat(balancePoints(customerId)).isEqualTo(expected);
        assertThat(ledgerSum).isEqualTo(expected);
    }

    @Test
    void credit_sameIdempotencyKey_creditedExactlyOnce() {
        long customerId = 1003L;
        String key = "order-complete:42";

        loyaltyCreditService.credit(customerId, 500, "ORDER_REWARD", key);
        LoyaltyCreditService.CreditOutcome replay =
                loyaltyCreditService.credit(customerId, 500, "ORDER_REWARD", key);

        assertThat(replay.replay()).isTrue();
        assertThat(balancePoints(customerId)).isEqualTo(500);
        assertThat(ledgerRepository.existsByReferenceId(key)).isTrue();
        assertThat(ledgerCount(customerId)).isEqualTo(1);
    }

    @Test
    void credit_overDailyCap_rejected() {
        long customerId = 1004L;

        loyaltyCreditService.credit(customerId, 9_000, "BULK", "cap-1");
        assertThatThrownBy(() -> loyaltyCreditService.credit(customerId, 2_000, "BULK", "cap-2"))
                .isInstanceOf(LoyaltyDailyCapExceededException.class);

        // The rejected credit must not have touched the ledger/balance.
        assertThat(balancePoints(customerId)).isEqualTo(9_000);
        assertThat(ledgerRepository.creditedSince(customerId, java.time.LocalDate.now().atStartOfDay()))
                .isEqualTo(9_000);
    }

    @Test
    void redeem_materializesMissingBalanceRowFromLedger() {
        long customerId = 1005L;
        // Ledger history with NO balance row (pre-V10 customers).
        jdbcTemplate.update("""
                INSERT INTO loyalty_points_ledger (customer_id, points, transaction_type, reason, created_at)
                VALUES (?, 400, 'CREDIT', 'LEGACY', now())
                """, customerId);

        assertThat(loyaltyService.redeemPoints(customerId, MIN_REDEMPTION)).isTrue();
        assertThat(balancePoints(customerId)).isEqualTo(300);
    }

    @Test
    void reconciliation_snapsDriftedBalancesBackToLedger_andRecordsMetric() {
        long customerId = 1006L;
        loyaltyService.creditPoints(customerId, 500, "SEED", null);
        double metricBefore = currentDriftMetric();

        // Simulate a lost update / external corruption.
        jdbcTemplate.update("UPDATE loyalty_point_balances SET points = 9999 WHERE customer_id = ?", customerId);
        assertThat(balancePoints(customerId)).isEqualTo(9999);

        LoyaltyReconciliationService.ReconciliationReport report = reconciliationService.reconcile();

        assertThat(report.correctedRows()).isEqualTo(1);
        assertThat(report.driftedCustomers()).isEqualTo(1);
        assertThat(report.totalDriftPoints()).isEqualTo(9_499);
        assertThat(balancePoints(customerId)).isEqualTo(500);

        // Idempotent: a second run finds nothing.
        LoyaltyReconciliationService.ReconciliationReport secondRun = reconciliationService.reconcile();
        assertThat(secondRun.correctedRows()).isZero();

        // Drift is observable via the loyalty_reconciliation_drift metric.
        assertThat(currentDriftMetric() - metricBefore).isEqualTo(9_499.0);
    }

    @Test
    void balanceAndLedgerStaysConsistent_underMixedTraffic() throws Exception {
        long customerId = 1007L;
        loyaltyService.creditPoints(customerId, 2_000, "SEED", null);

        runConcurrently(16, i -> {
            switch (i % 4) {
                case 0 -> loyaltyService.creditPoints(customerId, 10, "MIX", null);
                case 1 -> loyaltyService.redeemPoints(customerId, MIN_REDEMPTION);
                case 2 -> loyaltyService.getLoyaltyPoints(customerId);
                default -> loyaltyService.creditPoints(customerId, 20, "MIX", null);
            }
        });

        assertThat(balancePoints(customerId)).isEqualTo(ledgerRepository.ledgerBalance(customerId));
        assertThat(balancePoints(customerId)).isGreaterThanOrEqualTo(0);
    }

    // ---- helpers ----

    private long balancePoints(long customerId) {
        return balanceRepository.findByCustomerId(customerId)
                .map(com.bhukkad.growth.domain.entity.LoyaltyPointBalance::getPoints)
                .orElse(0L);
    }

    private int ledgerCount(long customerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM loyalty_points_ledger WHERE customer_id = ?",
                Integer.class, customerId);
        return count == null ? 0 : count;
    }

    private double currentDriftMetric() {
        io.micrometer.core.instrument.Counter counter = meterRegistry
                .find(LoyaltyReconciliationService.DRIFT_METRIC).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void runConcurrently(int threads, java.util.function.IntConsumer action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<? extends java.util.concurrent.Future<?>> futures = IntStream.range(0, threads)
                .<java.util.concurrent.Future<?>>mapToObj(i -> pool.submit(() -> {
                    try {
                        start.await();
                        action.accept(i);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }))
                .collect(Collectors.toList());
        start.countDown();
        for (java.util.concurrent.Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException
                     | java.util.concurrent.TimeoutException e) {
                // Fail-first: any worker assertion failure or stall fails the test.
                throw new IllegalStateException("Concurrent worker failed", e);
            }
        }
        pool.shutdown();
    }
}
