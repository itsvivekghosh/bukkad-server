package com.bhukkad.compliance;

import com.bhukkad.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Old-order archival job (Phase 2). Moves orders older than the retention
 * window from the hot {@code orders} table into the range-partitioned
 * {@code orders_archive} table (V55), so retention is enforced by cheap
 * partition pruning instead of a bulk DELETE. Runs under a ShedLock so only
 * one replica drains the archive queue.
 *
 * <p>The move is two statements per batch: {@code INSERT ... SELECT} into the
 * archive, then {@code DELETE} the same ids from {@code orders}. Both are
 * bounded by {@code batchSize} so each pass touches a small, committed slice.
 * MySQL partitioned tables cannot carry foreign keys, so archived rows are
 * copied (no FK) and the source rows deleted afterwards.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderArchiveService {

    private final OrderRepository orderRepository;
    private final OrderArchiveProperties archiveProperties;

    @Scheduled(fixedDelayString = "${app.archival.orders.interval-ms:3600000}")
    @SchedulerLock(name = "order-archive-job", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public Integer archiveOldOrders() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusDays(archiveProperties.getRetentionDays());
        int moved = orderRepository.archiveOrdersBefore(cutoff, archiveProperties.getBatchSize());
        if (moved > 0) {
            int deleted = orderRepository.deleteOrdersBefore(cutoff, moved);
            log.info("ORDER_ARCHIVE | cutoff={} | moved={} | deleted={}", cutoff, moved, deleted);
            return moved;
        }
        return 0;
    }
}
