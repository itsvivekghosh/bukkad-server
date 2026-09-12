package com.bhukkad.referral.integrity;

import com.bhukkad.referral.AbstractReferralPostgresTest;
import com.bhukkad.referral.domain.entity.ReferralRewardLedger;
import com.bhukkad.referral.domain.repository.ReferralRewardLedgerRepository;
import com.bhukkad.referral.domain.repository.UserReferralCodeRepository;
import com.bhukkad.referral.domain.service.ReferralService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fail-first integrity contract for the referral module (ADR-005 / audit
 * feature #4) against real PostgreSQL:
 *
 * <ol>
 *   <li>code generation is collision-safe and unique across customers (the
 *       {@code uk_referral_code} index is the authority; no id-modulo
 *       fallback);</li>
 *   <li>N concurrent applies for the same referee → exactly one binding and
 *       exactly one reward (partial unique index
 *       {@code uq_user_referral_codes_referred_by});</li>
 *   <li>re-apply after binding is idempotent: no re-bind, no second
 *       reward;</li>
 *   <li>completion rewards are idempotent by orderId/eventId and capped at
 *       one per referee.</li>
 * </ol>
 */
@SpringBootTest
class ReferralIntegrityPostgresTest extends AbstractReferralPostgresTest {

    @Autowired
    private ReferralService referralService;
    @Autowired
    private UserReferralCodeRepository referralCodeRepository;
    @Autowired
    private ReferralRewardLedgerRepository rewardLedgerRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM referral_rewards_ledger");
        jdbcTemplate.update("DELETE FROM user_referral_codes");
    }

    @Test
    void generatedCodes_uniqueAcrossCustomers_andShapeSafe() {
        Set<String> codes = IntStream.rangeClosed(1, 50)
                .mapToObj(i -> referralService.generateAndSaveReferralCode(10_000L + i))
                .collect(Collectors.toSet());

        assertThat(codes).hasSize(50);
        assertThat(codes).allMatch(code -> code.matches("BK[A-Z0-9]{10,}"));
        // Never an id % 10000 style tail: codes never END in a bare decimal
        // copy of the customer id.
        assertThat(codes).allSatisfy(code ->
                assertThat(code.endsWith(String.valueOf(10_000L))).isFalse());
    }

    @Test
    void concurrentApplies_sameReferee_exactlyOneBindingOneReward() throws Exception {
        String referrerCode = referralService.generateAndSaveReferralCode(2001L);
        long referee = 3001L;

        AtomicInteger firstBindings = new AtomicInteger();
        runConcurrently(12, i -> {
            ReferralService.ApplyReferralOutcome outcome =
                    referralService.applyReferral(referee, "referee@test.com", referrerCode);
            if (outcome.firstBinding()) {
                firstBindings.incrementAndGet();
            }
        });

        assertThat(firstBindings.get()).isEqualTo(1);
        assertThat(referralCodeRepository.countByReferredBy(2001L)).isEqualTo(1);
        assertThat(applyRewardRows(referee)).isEqualTo(1);
        assertThat(bonusEarned(2001L)).isEqualTo(50.0);
    }

    @Test
    void reApply_afterBound_idempotent_noDoubleReward() {
        String referrerCode = referralService.generateAndSaveReferralCode(2101L);
        long referee = 3101L;

        ReferralService.ApplyReferralOutcome first =
                referralService.applyReferral(referee, "referee@test.com", referrerCode);
        ReferralService.ApplyReferralOutcome second =
                referralService.applyReferral(referee, "referee@test.com", referrerCode);
        String secondReferrerCode = referralService.generateAndSaveReferralCode(2102L);
        ReferralService.ApplyReferralOutcome third =
                referralService.applyReferral(referee, "referee@test.com", secondReferrerCode);

        assertThat(first.firstBinding()).isTrue();
        assertThat(second.firstBinding()).isFalse();
        assertThat(third.firstBinding()).isFalse();
        assertThat(referralCodeRepository.countByReferredBy(2101L)).isEqualTo(1);
        assertThat(referralCodeRepository.countByReferredBy(2102L)).isZero();
        assertThat(applyRewardRows(referee)).isEqualTo(1);
        assertThat(bonusEarned(2101L)).isEqualTo(50.0);
        assertThat(bonusEarned(2102L)).isEqualTo(0.0);
    }

    @Test
    void concurrentApplies_differentReferrers_singleWinner() throws Exception {
        String referrerA = referralService.generateAndSaveReferralCode(2201L);
        String referrerB = referralService.generateAndSaveReferralCode(2202L);
        long referee = 3201L;

        runConcurrently(8, i ->
                referralService.applyReferral(referee, "referee@test.com", i % 2 == 0 ? referrerA : referrerB));

        long aCount = referralCodeRepository.countByReferredBy(2201L);
        long bCount = referralCodeRepository.countByReferredBy(2202L);
        assertThat(aCount + bCount).isEqualTo(1);
        assertThat(applyRewardRows(referee)).isEqualTo(1);
    }

    @Test
    void completeReferral_idempotentByOrderId_andCappedPerReferee() {
        String referrerCode = referralService.generateAndSaveReferralCode(2301L);
        long referee = 3301L;
        referralService.applyReferral(referee, "referee@test.com", referrerCode);

        ReferralService.CompletionOutcome first =
                referralService.completeReferral(referee, 42L);
        ReferralService.CompletionOutcome replay =
                referralService.completeReferral(referee, 42L);
        ReferralService.CompletionOutcome otherOrder =
                referralService.completeReferral(referee, 43L);

        assertThat(first.completed()).isTrue();
        assertThat(replay.completed()).isFalse();
        // A different orderId still cannot double-reward: one completion
        // reward per referee, ever ((referred_customer_id, reward_type) cap).
        assertThat(otherOrder.completed()).isFalse();
        List<ReferralRewardLedger> rows = rewardLedgerRepository.findAll().stream()
                .filter(r -> ReferralRewardLedger.TYPE_COMPLETION_BONUS.equals(r.getRewardType()))
                .toList();
        assertThat(rows).hasSize(1);
        assertThat(bonusEarned(2301L)).isEqualTo(50.0 + 25.0);
    }

    @Test
    void completeReferral_withoutBinding_isNoop() {
        referralService.generateAndSaveReferralCode(2401L);

        ReferralService.CompletionOutcome outcome = referralService.completeReferral(2401L, 99L);

        assertThat(outcome.completed()).isFalse();
        assertThat(rewardLedgerRepository.findAll()).isEmpty();
    }

    // ---- helpers ----

    private int applyRewardRows(long referee) {
        return (int) rewardLedgerRepository.findAll().stream()
                .filter(r -> referee == r.getReferredCustomerId())
                .filter(r -> ReferralRewardLedger.TYPE_APPLY_BONUS.equals(r.getRewardType()))
                .count();
    }

    private double bonusEarned(long customerId) {
        return referralCodeRepository.findByCustomerId(customerId)
                .map(row -> row.getReferralBonusEarned())
                .orElse(0.0);
    }

    private void runConcurrently(int threads, IntConsumer action) throws InterruptedException {
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
