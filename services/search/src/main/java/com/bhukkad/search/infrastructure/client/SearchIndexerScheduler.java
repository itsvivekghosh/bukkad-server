package com.bhukkad.search.infrastructure.client;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled flush for {@link OptimizedSearchIndexer}.
 *
 * <p>Flushes the indexer queue every 1s with ShedLock to prevent
 * concurrent flushes across multiple instances.</p>
 */
@Component
public class SearchIndexerScheduler {

    private final OptimizedSearchIndexer indexer;

    public SearchIndexerScheduler(OptimizedSearchIndexer indexer) {
        this.indexer = indexer;
    }

    @SchedulerLock(name = "searchIndexerFlush", lockAtMostFor = "PT2S", lockAtLeastFor = "PT0.5S")
    @Scheduled(fixedRate = 1_000)
    public void flush() {
        indexer.flush();
    }
}
