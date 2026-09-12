package com.bhukkad.search.domain.service.impl;

import com.bhukkad.search.config.SearchSyncProperties;
import com.bhukkad.search.domain.service.impl.SearchSyncProjectionService;
import com.bhukkad.search.infrastructure.client.SearchSourceClient;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * ADR-002 periodic reconciliation sweep: diffs the search read tables against
 * the restaurant service's source of truth and repairs drift that the
 * event-driven path missed (a lost outbox row, a DLT-parked event, a consumer
 * outage). Bounded: one source page of restaurants per cycle plus one menu
 * snapshot each — never a full reindex per tick.
 *
 * <p>ShedLock keeps a single runner across replicas (shedlock table, search
 * migration V10); each restaurant's repair commits in its own
 * {@link TransactionTemplate} transaction so one poisoned restaurant never
 * rolls back the batch. Repairs reuse the same idempotent upserts as the
 * consumer ({@link SearchSyncProjectionService}), and menu items projected but
 * absent from the source menu are removed (delete propagation).</p>
 */
@Component
@ConditionalOnProperty(name = "app.search.sync.enabled", havingValue = "true", matchIfMissing = true)
public class SearchReconciliationSweep {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SearchReconciliationSweep.class);

    private final SearchSourceClient sourceClient;
    private final SearchSyncProjectionService projectionService;
    private final MenuItemProjectionReader menuItemReader;
    private final SearchSyncProperties properties;
    private final TransactionTemplate transactionTemplate;

    public SearchReconciliationSweep(SearchSourceClient sourceClient,
                                     SearchSyncProjectionService projectionService,
                                     MenuItemProjectionReader menuItemReader,
                                     SearchSyncProperties properties,
                                     PlatformTransactionManager transactionManager) {
        this.sourceClient = sourceClient;
        this.projectionService = projectionService;
        this.menuItemReader = menuItemReader;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.search.sync.interval-ms:300000}")
    @SchedulerLock(name = "search-reconciliation-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void sweep() {
        List<SearchSourceClient.SourceRestaurant> page =
                sourceClient.restaurantPage(0, properties.getRestaurantsPerCycle());
        int repaired = 0;
        for (SearchSourceClient.SourceRestaurant source : page) {
            if (source.id() == null || source.id() <= 0) {
                continue;
            }
            final Long restaurantId = source.id();
            try {
                Integer count = transactionTemplate.execute(status ->
                        repairRestaurant(restaurantId));
                repaired += count == null ? 0 : count;
            } catch (Exception ex) {
                log.error("SEARCH_SYNC_SWEEP_RESTAURANT_FAILED | restaurantId={} | error={}",
                        restaurantId, ex.getMessage());
            }
        }
        if (!page.isEmpty()) {
            log.info("SEARCH_SYNC_SWEEP | restaurants={} | repairedRows={}", page.size(), repaired);
        }
    }

    /** Repairs one restaurant's projection rows; runs inside its own transaction. */
    int repairRestaurant(Long restaurantId) {
        SearchSourceClient.SourceMenu menu = sourceClient.menu(restaurantId);
        if (menu == null) {
            return 0; // source unreachable/deleted: leave rows, retry next cycle
        }
        int repaired = 0;
        for (SearchSourceClient.SourceMenuItem item : menu.items()) {
            if (item.id() == null) {
                continue;
            }
            Integer before = menuItemReader.countById(item.id());
            projectionService.upsertMenuItemFromSource(item.id(), restaurantId, menu.restaurantName(),
                    item.name(), item.description(), item.price(), item.available());
            repaired++;
            log.debug("SEARCH_SYNC_REPAIR_ITEM | id={} | existed={}", item.id(), before != null && before > 0);
        }
        // Delete propagation for the sweep: projected items of this restaurant
        // that the source menu no longer contains are stale — remove them.
        List<Long> projectedIds = menuItemReader.idsForRestaurant(restaurantId);
        List<Long> sourceIds = menu.items().stream()
                .map(SearchSourceClient.SourceMenuItem::id)
                .filter(id -> id != null)
                .toList();
        for (Long projectedId : projectedIds) {
            if (!sourceIds.contains(projectedId)) {
                projectionService.deleteMenuItem(projectedId, null);
                repaired++;
                log.info("SEARCH_SYNC_SWEEP_REMOVED_STALE | itemId={} | restaurantId={}",
                        projectedId, restaurantId);
            }
        }
        return repaired;
    }
}
