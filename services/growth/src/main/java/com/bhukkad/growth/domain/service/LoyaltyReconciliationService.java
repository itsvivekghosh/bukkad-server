package com.bhukkad.growth.domain.service;

import com.bhukkad.growth.domain.entity.LoyaltyPointBalance;
import com.bhukkad.growth.domain.repository.LoyaltyPointBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Nightly loyalty reconciliation (ADR-005 / audit feature #3): the ledger
 * sum is recomputed per customer and snapped into the balance rows; any
 * drift is reported through WARN/ERROR logs, the
 * {@code loyalty_reconciliation_drift} metric and a forced cache refresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoyaltyReconciliationService {

    public static final String DRIFT_METRIC = "loyalty_reconciliation_drift";
    private static final String LOYALTY_KEY_PREFIX = "loyalty:points:";
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private final LoyaltyPointBalanceRepository balanceRepository;
    private final StringRedisTemplate redisTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    /**
     * Corrects every drifted balance row in one set-based statement and
     * reports the drift. Idempotent: a second run right after the first is a
     * no-op.
     */
    @Transactional
    public ReconciliationReport reconcile() {
        List<LoyaltyPointBalanceRepository.DriftProjection> drifted =
                balanceRepository.findDriftedBalances();
        int corrected = balanceRepository.reconcileAllFromLedger();
        // Customers with ledger history but no balance row yet are harmless:
        // their first credit/materialize/read creates the row from the ledger.

        long totalDriftPoints = drifted.stream().mapToLong(d -> Math.abs(d.getDrift())).sum();
        if (!drifted.isEmpty()) {
            log.error("LOYALTY_RECONCILIATION_DRIFT correctedRows={} customers={} totalDriftPoints={}",
                    corrected, drifted.size(), totalDriftPoints);
        } else {
            log.info("LOYALTY_RECONCILIATION_CLEAN correctedRows={}", corrected);
        }

        if (meterRegistry != null && totalDriftPoints > 0) {
            io.micrometer.core.instrument.Counter counter =
                    meterRegistry.counter(DRIFT_METRIC);
            counter.increment(totalDriftPoints);
        }

        drifted.forEach(drift -> refreshCache(drift.getCustomerId()));
        return new ReconciliationReport(corrected, drifted.size(), totalDriftPoints);
    }

    /** Force the Redis cache back in sync with the corrected rows. */
    private void refreshCache(Long customerId) {
        try {
            balanceRepository.findByCustomerId(customerId).ifPresent(balance ->
                    redisTemplate.opsForValue().set(
                            LOYALTY_KEY_PREFIX + customerId,
                            Long.toString(balance.getPoints()), CACHE_TTL));
        } catch (org.springframework.data.redis.RedisConnectionFailureException ex) {
            log.warn("Reconciliation cache refresh skipped customerId={}", customerId);
        }
    }

    public record ReconciliationReport(int correctedRows, int driftedCustomers, long totalDriftPoints) {
    }
}
