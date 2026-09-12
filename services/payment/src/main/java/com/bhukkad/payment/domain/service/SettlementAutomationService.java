package com.bhukkad.payment.domain.service;

import com.bhukkad.payment.domain.repository.RestaurantSettlementRepository;
import com.bhukkad.payment.domain.entity.SettlementRun;
import com.bhukkad.payment.domain.repository.SettlementRunRepository;
import com.bhukkad.payment.config.SettlementAutomationProperties;
import com.bhukkad.payment.domain.event.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Automated settlement batch (migration plan W2 / G1 port of the monolith
 * {@code SettlementAutomationScheduler}).
 *
 * <p>Differences from the monolith implementation, by design:</p>
 * <ul>
 *   <li>Restaurants are enumerated from payment-owned {@code restaurant_settlements}
 *       rows in PENDING status — payment never reads another domain's tables
 *       (ownership matrix §3), so no {@code Restaurant}/{@code DeliveryAgent}
 *       repositories are involved. Rider payout automation stays with the
 *       monolith until the order-deduction events land (see rollback register).</li>
 *   <li>Each settlement flip is status-guarded by
 *       {@link RestaurantSettlementRepository#atomicSettleByRestaurant}, so a
 *       duplicate tick from a rolling-release overlap settles nothing twice —
 *       no cluster lease is required for correctness, only a cheap run-date
 *       pre-check to avoid redundant audit rows.</li>
 *   <li>Completion is announced on the outbox
 *       ({@code payment.settlement.run.completed}) instead of being queried
 *       synchronously.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementAutomationService {

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_SETTLED = "SETTLED";
    static final String RUN_COMPLETED = "COMPLETED";

    private final SettlementRunRepository runRepository;
    private final RestaurantSettlementRepository settlementRepository;
    private final SettlementAutomationProperties properties;
    private final PaymentEventPublisher eventPublisher;

    /**
     * Runs the daily batch for {@code runDate}. A no-op when the date was already
     * settled (manual runs count too — they pin the date).
     *
     * <p>Single transaction: the flips and the run audit row commit together, so
     * a crash mid-batch leaves everything PENDING exactly as before (the atomic
     * flips are the only writes).</p>
     */
    @Transactional
    public Optional<AutomationResult> settleFor(LocalDate runDate) {
        if (runRepository.existsByRunDate(runDate)) {
            log.debug("SETTLEMENT_AUTOMATION_SKIPPED | date={} | run already exists", runDate);
            return Optional.empty();
        }

        List<Long> restaurantIds =
                settlementRepository.findDistinctRestaurantIdsByStatus(STATUS_PENDING);
        if (restaurantIds.size() > properties.getBatchLimit()) {
            // Deterministic resumption: next tick continues from the untouched tail.
            restaurantIds = restaurantIds.subList(0, properties.getBatchLimit());
            log.info("SETTLEMENT_AUTOMATION_CAPPED | date={} | limit={}", runDate, properties.getBatchLimit());
        }

        var totalNet = BigDecimal.ZERO;
        var restaurantsSettled = 0;
        var rowsSettled = 0;

        for (Long restaurantId : restaurantIds) {
            BigDecimal pending = settlementRepository
                    .sumNetAmountByRestaurantAndStatus(restaurantId, STATUS_PENDING);
            if (pending.compareTo(properties.getMinPendingAmount()) < 0) {
                continue;
            }
            int flipped = settlementRepository.atomicSettleByRestaurant(
                    restaurantId, STATUS_PENDING, STATUS_SETTLED);
            if (flipped > 0) {
                restaurantsSettled++;
                rowsSettled += flipped;
                totalNet = totalNet.add(pending);
            }
        }

        SettlementRun run = new SettlementRun();
        run.setRunDate(runDate);
        run.setTotalAmount(totalNet);
        run.setStatus(RUN_COMPLETED);
        run = runRepository.save(run);

        eventPublisher.settlementRunCompleted(run.getId(), runDate, restaurantsSettled,
                rowsSettled, totalNet);
        log.info("SETTLEMENT_AUTOMATION_COMPLETED | runId={} | date={} | restaurants={} | rows={} | net={}",
                run.getId(), runDate, restaurantsSettled, rowsSettled, totalNet);
        return Optional.of(new AutomationResult(run, restaurantsSettled, rowsSettled, totalNet));
    }

    /** Outcome summary of one automated batch tick. */
    public record AutomationResult(SettlementRun run, int restaurantsSettled,
                                   int rowsSettled, BigDecimal totalNet) {
    }
}
