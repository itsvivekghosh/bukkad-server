package com.bhukkad.order.domain.service.impl;

import com.bhukkad.order.domain.repository.CartItemRepository;
import com.bhukkad.order.domain.repository.CartRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Stale-cart recovery (port of monolith {@code CartRecoveryService}): expires
 * carts untouched past the TTL so they cannot be checked out with stale items.
 *
 * <p>PERF-3 (V-carts): the sweep is SQL-predicated ({@code WHERE status =
 * 'ACTIVE' AND updated_at < :cutoff ORDER BY id} through
 * {@link CartRepository#findIdsByStatusAndUpdatedAtBefore}), batched at
 * {@value #SWEEP_BATCH_SIZE} carts per statement pair, and each batch commits
 * in its own transaction (bounded undo/redo, no long row locks). The old code
 * materialized {@code findAll()} and saved per cart — O(all carts) per tick —
 * and {@code expireStale} was never scheduled, so carts never expired; both
 * are fixed here, and the hourly sweeper runs under ShedLock single-runner.</p>
 */
@Service
@Slf4j
public class CartRecoveryService {

    /** Carts flipped per sweep batch (guide §6 PERF-3.5: ≤500 rows per tx). */
    static final int SWEEP_BATCH_SIZE = 500;
    /** Safety bound: batches per invocation before yielding to the next tick. */
    static final int MAX_BATCHES_PER_RUN = 200;

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final TransactionTemplate writeTxTemplate;
    private final int staleTtlHours;

    public CartRecoveryService(CartRepository cartRepository,
                               CartItemRepository cartItemRepository,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.carts.stale-ttl-hours:24}") int staleTtlHours) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.writeTxTemplate = new TransactionTemplate(transactionManager);
        this.staleTtlHours = staleTtlHours;
    }

    /**
     * Hourly stale-cart sweeper. Previously unreached (grep showed expireStale
     * only from tests), which is why cart rows accumulated forever.
     */
    @Scheduled(cron = "${app.carts.sweeper-cron:0 0 * * * *}")
    @SchedulerLock(name = "cart-stale-sweeper", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void sweepStaleCarts() {
        int expired = expireStale(staleTtlHours);
        if (expired > 0) {
            log.info("CART_SWEEP expired={} ttlHours={}", expired, staleTtlHours);
        }
    }

    /**
     * Expires every ACTIVE cart untouched for {@code ttlHours}. Returns the
     * number of carts flipped to CHECKED_OUT (monolith-compatible semantics:
     * line items of expired carts are dropped, the cart row is kept).
     */
    public int expireStale(int ttlHours) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(ttlHours);
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<Long> staleIds = cartRepository.findIdsByStatusAndUpdatedAtBefore(
                    CartService.STATUS_ACTIVE, cutoff, PageRequest.of(0, SWEEP_BATCH_SIZE));
            if (staleIds.isEmpty()) {
                break;
            }
            Integer processed = writeTxTemplate.execute(status -> {
                cartItemRepository.deleteByCartIdIn(staleIds);
                return cartRepository.updateStatusByIds(
                        staleIds, CartService.STATUS_CHECKED_OUT, LocalDateTime.now());
            });
            total += processed == null ? 0 : processed;
            if (staleIds.size() < SWEEP_BATCH_SIZE) {
                break; // drained
            }
        }
        return total;
    }
}
