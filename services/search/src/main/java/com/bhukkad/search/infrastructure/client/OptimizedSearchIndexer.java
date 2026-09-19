package com.bhukkad.search.infrastructure.client;

import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import com.bhukkad.search.domain.entity.RestaurantSearchEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Bulk indexer for Elasticsearch with async flushing.
 *
 * <p>Buffers index operations and flushes them in batches of 5K every 1s
 * to optimize Elasticsearch throughput.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OptimizedSearchIndexer {

    private static final int QUEUE_CAPACITY = 10_000;
    private static final int BATCH_SIZE = 5_000;
    private static final long FLUSH_INTERVAL_MS = 1_000;

    private final ObjectProvider<co.elastic.clients.elasticsearch.ElasticsearchClient> esClientProvider;
    private final LinkedBlockingQueue<IndexOperation> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);

    /**
     * Enqueue a restaurant for indexing.
     */
    public void indexRestaurant(RestaurantSearchEntity entity) {
        if (!queue.offer(new RestaurantIndexOp(entity))) {
            log.warn("SEARCH_INDEXER_QUEUE_FULL dropping restaurantId={}", entity.getId());
        }
    }

    /**
     * Enqueue a menu item for indexing.
     */
    public void indexMenuItem(com.bhukkad.search.domain.entity.MenuItemSearchEntity entity) {
        if (!queue.offer(new MenuItemIndexOp(entity))) {
            log.warn("SEARCH_INDEXER_QUEUE_FULL dropping menuItemId={}", entity.getId());
        }
    }

    /**
     * Enqueue a delete operation for a restaurant.
     */
    public void deleteRestaurant(Long id) {
        if (!queue.offer(new RestaurantDeleteOp(id))) {
            log.warn("SEARCH_INDEXER_QUEUE_FULL dropping delete restaurantId={}", id);
        }
    }

    /**
     * Enqueue a delete operation for a menu item.
     */
    public void deleteMenuItem(Long id) {
        if (!queue.offer(new MenuItemDeleteOp(id))) {
            log.warn("SEARCH_INDEXER_QUEUE_FULL dropping delete menuItemId={}", id);
        }
    }

    /**
     * Flush the indexer queue. Called by {@link SearchIndexerScheduler}.
     */
    void flush() {
        List<IndexOperation> batch = new ArrayList<>(BATCH_SIZE);
        queue.drainTo(batch, BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }

        co.elastic.clients.elasticsearch.ElasticsearchClient client = esClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("SEARCH_INDEXER_NO_ES_CLIENT batchSize={}", batch.size());
            return;
        }

        try {
            BulkRequest.Builder bulkBuilder = new BulkRequest.Builder();
            for (IndexOperation op : batch) {
                try {
                    op.apply(bulkBuilder);
                } catch (Exception ex) {
                    log.warn("SEARCH_INDEXER_OP_FAILED error={}", ex.getMessage());
                }
            }

            if (!bulkBuilder.build().operations().isEmpty()) {
                BulkResponse response = client.bulk(bulkBuilder.build());
                if (response.errors()) {
                    log.warn("SEARCH_INDEXER_BATCH_ERRORS batchSize={}", batch.size());
                } else {
                    log.debug("SEARCH_INDEXER_BATCH_OK batchSize={}", batch.size());
                }
            }
        } catch (Exception ex) {
            log.error("SEARCH_INDEXER_FLUSH_FAILED batchSize={} error={}", batch.size(), ex.getMessage());
        }
    }

    // ==================== Index Operations ====================

    interface IndexOperation {
        void apply(BulkRequest.Builder builder) throws Exception;
    }

    record RestaurantIndexOp(RestaurantSearchEntity entity) implements IndexOperation {
        @Override
        public void apply(BulkRequest.Builder builder) {
            builder.operations(op -> op
                    .index(idx -> idx
                            .index("restaurants")
                            .id(String.valueOf(entity.getId()))
                            .document(entity)
                    )
            );
        }
    }

    record MenuItemIndexOp(com.bhukkad.search.domain.entity.MenuItemSearchEntity entity) implements IndexOperation {
        @Override
        public void apply(BulkRequest.Builder builder) {
            builder.operations(op -> op
                    .index(idx -> idx
                            .index("menu_items")
                            .id(String.valueOf(entity.getId()))
                            .document(entity)
                    )
            );
        }
    }

    record RestaurantDeleteOp(Long id) implements IndexOperation {
        @Override
        public void apply(BulkRequest.Builder builder) {
            builder.operations(op -> op
                    .delete(del -> del
                            .index("restaurants")
                            .id(String.valueOf(id))
                    )
            );
        }
    }

    record MenuItemDeleteOp(Long id) implements IndexOperation {
        @Override
        public void apply(BulkRequest.Builder builder) {
            builder.operations(op -> op
                    .delete(del -> del
                            .index("menu_items")
                            .id(String.valueOf(id))
                    )
            );
        }
    }
}
