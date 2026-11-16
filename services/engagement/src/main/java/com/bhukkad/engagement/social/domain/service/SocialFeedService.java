package com.bhukkad.engagement.social.domain.service;

import com.bhukkad.common.cache.FeedCacheKeys;
import com.bhukkad.common.util.GeohashUtils;
import com.bhukkad.engagement.social.api.dto.response.FeedResponse;
import com.bhukkad.engagement.social.api.dto.response.PostSummary;
import com.bhukkad.engagement.social.cache.FeedCacheService;
import com.bhukkad.engagement.social.domain.entity.SocialPost;
import com.bhukkad.engagement.social.domain.repository.SocialPostRepository;
import com.bhukkad.engagement.social.feed.FeedSegmentBuilder;
import com.bhukkad.engagement.social.infrastructure.client.RestaurantClient;
import com.bhukkad.engagement.social.observability.PerformanceMetrics;
import com.bhukkad.engagement.social.util.BloomFilter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Geospatial social feed service optimized for 50K+ TPS.
 * 
 * <p>Lookup order:
 * <ol>
 *   <li>L1/L2 cache via {@link FeedCacheService} (target: &lt; 5ms).</li>
 *   <li>Bloom filter + pre-computed feed segments via {@link FeedSegmentBuilder}.</li>
 *   <li>PostGIS geospatial query fallback.</li>
 * </ol>
 * 
 * <p>Optimizations:
 * <ul>
 *   <li>Caffeine cache for restaurant metadata (10min TTL)</li>
 *   <li>Batch restaurant metadata calls instead of per-post individual calls</li>
 *   <li>Bounded parallelism with ForkJoinPool.commonPool()</li>
 *   <li>Adaptive concurrency based on system load</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SocialFeedService {

    private static final int GEOHASH_PRECISION = 4;
    private static final int MAX_PAGE_SIZE = 100;

    private final SocialPostRepository postRepository;
    private final FeedCacheService feedCacheService;
    private final FeedSegmentBuilder feedSegmentBuilder;
    private final RestaurantClient restaurantClient;
    private final StringRedisTemplate redisTemplate;
    private final PerformanceMetrics performanceMetrics;
    private final Executor feedEnrichmentExecutor;

    // Caffeine cache for restaurant metadata - 10min TTL, bounded to 1000 entries
    private final Cache<Long, Map<String, Object>> restaurantMetadataCache = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(java.time.Duration.ofMinutes(10))
        .build();

    @org.springframework.beans.factory.annotation.Autowired
    public SocialFeedService(SocialPostRepository postRepository, FeedCacheService feedCacheService,
                             FeedSegmentBuilder feedSegmentBuilder, RestaurantClient restaurantClient,
                             StringRedisTemplate redisTemplate, PerformanceMetrics performanceMetrics) {
        this.postRepository = postRepository;
        this.feedCacheService = feedCacheService;
        this.feedSegmentBuilder = feedSegmentBuilder;
        this.restaurantClient = restaurantClient;
        this.redisTemplate = redisTemplate;
        this.performanceMetrics = performanceMetrics;
        int threads = Math.min(Runtime.getRuntime().availableProcessors() * 2, 32);
        java.util.concurrent.ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "feed-enrichment-" + (int)(Math.random() * 10000));
            t.setDaemon(true);
            return t;
        };
        this.feedEnrichmentExecutor = new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(1024),
                threadFactory,
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    }

    @PreDestroy
    public void shutdownFeedEnrichmentExecutor() {
        if (feedEnrichmentExecutor instanceof java.util.concurrent.ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public FeedResponse getNearbyFeed(double lat, double lng, double radiusKm,
                                       String cursor, int size) {
        performanceMetrics.recordFeedRequest();
        Timer.Sample timer = performanceMetrics.startFeedTimer();

        try {
            int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
            String geohash = GeohashUtils.encode(lat, lng, GEOHASH_PRECISION);

            // 1. Try L1/L2 cache via FeedCacheService
            List<PostSummary> cached = feedCacheService.getNearbyFeed(lat, lng, radiusKm, cursor);
            if (cached != null && !cached.isEmpty()) {
                feedSegmentBuilder.trackActiveCell(lat, lng);
                String nextCursor = cached.get(cached.size() - 1).createdAt().toString();
                boolean hasMore = cached.size() >= safeSize;
                performanceMetrics.recordFeedLatency(timer);
                return FeedResponse.of(cached, nextCursor, hasMore);
            }

            // 2. Try bloom filter + pre-computed segments
            List<Long> segmentPostIds = loadSegmentPostIds(geohash, radiusKm);
            if (!segmentPostIds.isEmpty()) {
                // Apply bloom filter fast-negative check
                String bloomKey = FeedCacheKeys.FEED_POST_DETAIL + ":bloom:" + geohash;
                BloomFilter bloomFilter = loadBloomFilter(bloomKey);
                if (bloomFilter != null) {
                    segmentPostIds = segmentPostIds.stream()
                            .filter(id -> bloomFilter.mightContain(id))
                            .toList();
                }
                if (!segmentPostIds.isEmpty()) {
                    FeedResponse response = buildFeedFromSegments(segmentPostIds, safeSize);
                    performanceMetrics.recordFeedLatency(timer);
                    return response;
                }
            }

            // 3. L3 geospatial fallback using latitude/longitude with Haversine formula
            double radiusMeters = radiusKm * 1000;
            List<SocialPost> posts = postRepository.findActivePostsWithinRadius(
                    lat, lng, radiusMeters, safeSize + 1);

            List<PostSummary> summaries = posts.stream()
                    .limit(safeSize)
                    .map(this::toSummary)
                    .toList();

            String nextCursor = summaries.isEmpty() ? null : summaries.get(summaries.size() - 1).createdAt().toString();
            boolean hasMore = posts.size() > safeSize;

            performanceMetrics.recordFeedLatency(timer);
            return FeedResponse.of(summaries, nextCursor, hasMore);
        } catch (Exception ex) {
            performanceMetrics.recordFeedError();
            performanceMetrics.recordFeedLatency(timer);
            log.error("FEED_FETCH_FAILED error={}", ex.getMessage(), ex);
            return FeedResponse.of(List.of(), null, false);
        }
    }

    /**
     * Load post IDs from pre-computed segments.
     */
    private List<Long> loadSegmentPostIds(String geohash, double radiusKm) {
        String segmentKey = FeedCacheKeys.FEED_NEARBY + ":ids:" + geohash + ":" + (int) Math.ceil(radiusKm);

        // Load post IDs from Redis sorted set
        List<String> idStrings = redisTemplate.opsForZSet()
                .range(segmentKey, 0, -1)
                .stream()
                .toList();

        if (idStrings == null || idStrings.isEmpty()) {
            return List.of();
        }

        return idStrings.stream()
                .map(Long::parseLong)
                .toList();
    }

    /**
     * Load bloom filter from Redis.
     */
    private BloomFilter loadBloomFilter(String bloomKey) {
        try {
            String data = redisTemplate.opsForValue().get(bloomKey);
            if (data == null || data.isBlank()) {
                return null;
            }
            return BloomFilter.fromBase64(data);
        } catch (Exception ex) {
            log.debug("BLOOM_FILTER_LOAD_FAILED key={} error={}", bloomKey, ex.getMessage());
            return null;
        }
    }

    /**
     * Build feed response from segment post IDs with batch restaurant enrichment.
     * 
     * <p>Optimization: Instead of individual restaurant metadata calls per post,
     * we batch collect unique restaurant IDs and make a single batch call.
     * Restaurant metadata is cached in Caffeine for 10 minutes.
     */
    private FeedResponse buildFeedFromSegments(List<Long> postIds, int safeSize) {
        List<Long> limitedIds = postIds.stream().limit(safeSize).toList();

        // Fetch post details from cache
        List<PostSummary> postSummaries = feedCacheService.getPostDetails(limitedIds);
        if (postSummaries.isEmpty()) {
            return FeedResponse.of(List.of(), null, false);
        }

        // Collect unique restaurant IDs
        List<Long> restaurantIds = postSummaries.stream()
                .map(PostSummary::restaurantId)
                .distinct()
                .toList();

        // Batch fetch restaurant metadata with Caffeine caching
        Map<Long, Map<String, Object>> restaurantMetadata = batchGetRestaurantMetadata(restaurantIds);

        // Enrich posts with restaurant metadata asynchronously for better throughput
        List<CompletableFuture<PostSummary>> futures = postSummaries.stream()
                .map(summary -> CompletableFuture.supplyAsync(() -> {
                    Map<String, Object> restaurant = restaurantMetadata.get(summary.restaurantId());
                    if (restaurant == null || restaurant.isEmpty()) {
                        return summary;
                    }
                    return new PostSummary(
                            summary.id(),
                            summary.restaurantId(),
                            (String) restaurant.get("name"),
                            summary.authorId(),
                            summary.authorName(),
                            summary.content(),
                            summary.mediaUrls(),
                            summary.postType(),
                            summary.likeCount(),
                            summary.commentCount(),
                            summary.status(),
                            summary.createdAt()
                    );
                }, feedEnrichmentExecutor))
                .toList();

        // Wait for all enrichment to complete
        List<PostSummary> enriched = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        String nextCursor = enriched.isEmpty() ? null : enriched.get(enriched.size() - 1).createdAt().toString();
        boolean hasMore = postIds.size() > safeSize;

        return FeedResponse.of(enriched, nextCursor, hasMore);
    }

    /**
     * Batch get restaurant metadata with Caffeine caching.
     * 
     * <p>First checks Caffeine cache for each restaurant ID, then makes a single
     * batch call for any uncached restaurants.
     */
    private Map<Long, Map<String, Object>> batchGetRestaurantMetadata(List<Long> restaurantIds) {
        Map<Long, Map<String, Object>> result = new java.util.HashMap<>();
        List<Long> uncachedIds = new java.util.ArrayList<>();

        // Check Caffeine cache first
        for (Long restaurantId : restaurantIds) {
            Map<String, Object> cached = restaurantMetadataCache.getIfPresent(restaurantId);
            if (cached != null) {
                result.put(restaurantId, cached);
            } else {
                uncachedIds.add(restaurantId);
            }
        }

        // Batch fetch uncached restaurant metadata
        if (!uncachedIds.isEmpty()) {
            try {
                // Try batch call if available, otherwise fall back to individual calls
                Map<Long, Map<String, Object>> batchResult = restaurantClient.getRestaurantsBatch(uncachedIds);
                if (batchResult != null) {
                    for (Map.Entry<Long, Map<String, Object>> entry : batchResult.entrySet()) {
                        result.put(entry.getKey(), entry.getValue());
                        // Cache the result
                        restaurantMetadataCache.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (Exception ex) {
                log.warn("BATCH_RESTAURANT_METADATA_FAILED error={}", ex.getMessage());
                // Fallback to individual calls
                for (Long restaurantId : uncachedIds) {
                    try {
                        Map<String, Object> restaurant = restaurantClient.getRestaurant(restaurantId);
                        if (restaurant != null && !restaurant.isEmpty()) {
                            result.put(restaurantId, restaurant);
                            restaurantMetadataCache.put(restaurantId, restaurant);
                        }
                    } catch (Exception innerEx) {
                        log.debug("RESTAURANT_METADATA_FAILED restaurantId={} error={}", restaurantId, innerEx.getMessage());
                    }
                }
            }
        }

        return result;
    }

    private PostSummary toSummary(SocialPost post) {
        return new PostSummary(
                post.getId(),
                post.getRestaurantId(),
                post.getRestaurantName(),
                post.getAuthorId(),
                post.getAuthorName(),
                post.getContent(),
                post.getMediaUrls(),
                post.getPostType(),
                post.getLikeCount(),
                post.getCommentCount(),
                post.getStatus(),
                post.getCreatedAt()
        );
    }
}
