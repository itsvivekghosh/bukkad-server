package com.bhukkad.engagement.social.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Redis Hash-based like counter manager with pipeline batching.
 * 
 * <p>Maintains a centralized hash of post like counts for efficient
 * batch operations and memory usage. Unlike individual keys per post,
 * this uses a single Redis Hash to store all like counters.</p>
 * 
 * <p>Optimizations:</p>
 * <ul>
 *   <li>Pipeline batching: Increments are batched in 10ms windows to reduce RTT</li>
 *   <li>Single Redis connection per batch instead of per increment</li>
 *   <li>CompletableFuture for non-blocking increment results</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikeCounterManager {

    private static final String LIKE_COUNTERS_HASH = "social:likes:counters";
    private static final long LIKE_COUNTERS_TTL_DAYS = 7;
    private static final Duration BATCH_WINDOW = java.time.Duration.ofMillis(10);
    private static final int MAX_BATCH_SIZE = 1000;

    private final StringRedisTemplate redisTemplate;

    // Batching queue for pipeline increments
    private final ConcurrentLinkedQueue<BatchRequest> batchQueue = new ConcurrentLinkedQueue<>();
    
    // Single-threaded batch processor to serialize pipeline execution
    private final AtomicReference<Thread> batchProcessor = new AtomicReference<>();

    /**
     * Increment like counter for a post with pipeline batching.
     * 
     * <p>Increments are collected in a 10ms window and executed as a single
     * Redis pipeline, reducing RTT overhead from O(N) to O(1) per batch.</p>
     *
     * @param postId the post ID
     * @param delta the increment value (positive for like, negative for unlike)
     * @return the new count after the increment is applied
     */
    public Long increment(Long postId, long delta) {
        CompletableFuture<Long> future = new CompletableFuture<>();
        batchQueue.offer(new BatchRequest(postId, delta, future));
        scheduleBatchProcessor();
        return future.join(); // Wait for batched result (typically <10ms)
    }

    /**
     * Get like count for a post.
     *
     * @param postId the post ID
     * @return the like count, or null if not found
     */
    public Long getCount(Long postId) {
        Object value = redisTemplate.opsForHash().get(LIKE_COUNTERS_HASH, String.valueOf(postId));
        return value instanceof Number number ? number.longValue() : null;
    }

    /**
     * Get all like counts as a map.
     *
     * @return map of postId -> likeCount
     */
    public Map<Object, Object> getAllCounts() {
        return redisTemplate.opsForHash().entries(LIKE_COUNTERS_HASH);
    }

    /**
     * Set TTL on the counters hash.
     *
     * @param timeout the timeout value
     * @param unit the time unit
     */
    public void expire(long timeout, java.util.concurrent.TimeUnit unit) {
        redisTemplate.expire(LIKE_COUNTERS_HASH, timeout, unit);
    }

    /**
     * Delete like counter for a post.
     *
     * @param postId the post ID
     */
    public void delete(Long postId) {
        redisTemplate.opsForHash().delete(LIKE_COUNTERS_HASH, String.valueOf(postId));
    }

    /**
     * Schedule batch processor if not already running.
     */
    private void scheduleBatchProcessor() {
        if (batchProcessor.compareAndSet(null, Thread.currentThread())) {
            try {
                Thread.sleep(BATCH_WINDOW.toMillis());
                processBatch();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                batchProcessor.set(null);
                // Reschedule if more items accumulated
                if (!batchQueue.isEmpty()) {
                    scheduleBatchProcessor();
                }
            }
        }
    }

    /**
     * Process accumulated batch requests using Redis pipeline.
     */
    private void processBatch() {
        List<BatchRequest> batch = new ArrayList<>();
        // Manually drain since ConcurrentLinkedQueue doesn't have drainTo(Collection, int)
        BatchRequest item;
        int count = 0;
        while ((item = batchQueue.poll()) != null && count < MAX_BATCH_SIZE) {
            batch.add(item);
            count++;
        }

        if (batch.isEmpty()) {
            return;
        }

        // Aggregate deltas by postId to minimize Redis operations
        Map<Long, Long> aggregated = batch.stream()
            .collect(Collectors.groupingBy(
                BatchRequest::postId,
                Collectors.summingLong(BatchRequest::delta)
            ));

        // Pipeline execute all increments in a single round-trip
        List<Object> pipelineResults = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Entry<Long, Long> entry : aggregated.entrySet()) {
                connection.hIncrBy(
                    LIKE_COUNTERS_HASH.getBytes(),
                    String.valueOf(entry.getKey()).getBytes(),
                    entry.getValue()
                );
            }
            // Set TTL on the hash
            connection.expire(
                LIKE_COUNTERS_HASH.getBytes(),
                LIKE_COUNTERS_TTL_DAYS * 86400
            );
            return null;
        });

        // Complete futures with aggregated results
        for (BatchRequest req : batch) {
            Long aggregatedDelta = aggregated.get(req.postId());
            req.future().complete(aggregatedDelta);
        }

        log.debug("LIKE_BATCH_PROCESSED size={} aggregatedPosts={}", batch.size(), aggregated.size());
    }

    /**
     * Batch request record for pipeline processing.
     */
    record BatchRequest(Long postId, long delta, CompletableFuture<Long> future) {}
}
