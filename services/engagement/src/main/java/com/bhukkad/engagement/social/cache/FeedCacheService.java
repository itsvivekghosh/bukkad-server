package com.bhukkad.engagement.social.cache;

import com.bhukkad.common.cache.FeedCacheKeys;
import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.common.util.GeohashUtils;
import com.bhukkad.engagement.social.api.dto.response.PostSummary;
import com.bhukkad.engagement.social.domain.entity.SocialPost;
import com.bhukkad.engagement.social.domain.repository.SocialPostRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * FeedCacheService with L1/L2 hierarchy and request coalescing.
 * 
 * <p>Optimizations:
 * <ul>
 *   <li>L1: Caffeine in-process micro-cache (10ms TTL, 1000 entries)</li>
 *   <li>L2: Redis cache via RedisCacheService</li>
 *   <li>Request coalescing: ConcurrentHashMap with CompletableFuture for single-flight per key</li>
 *   <li>Off-heap serialization option for large feeds</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeedCacheService {

    private final SocialPostRepository postRepository;
    private final ObjectProvider<RedisCacheService> redisCacheServiceProvider;

    private static final int GEOHASH_PRECISION = 4;
    private static final long FEED_L1_TTL_MS = 10;
    private static final long FEED_L2_TTL_SECONDS = 600;
    private static final int DEFAULT_LIMIT = 100;
    private static final int L1_MAX_SIZE = 1_000;
    private static final int L1_MAX_WEIGHT = 50_000_000; // 50MB off-heap

    // L1: In-process Caffeine cache (heap objects for fast access)
    private final Cache<String, List<PostSummary>> l1Cache = Caffeine.newBuilder()
        .maximumSize(L1_MAX_SIZE)
        .expireAfterWrite(Duration.ofMillis(FEED_L1_TTL_MS))
        .recordStats()
        .build();

    // L1 serialized: off-heap byte[] cache for large feeds (reduces GC pressure)
    private final Cache<String, byte[]> l1CacheSerialized = Caffeine.newBuilder()
        .maximumWeight(L1_MAX_WEIGHT)
        .weigher((k, v) -> ((byte[]) v).length)
        .expireAfterWrite(Duration.ofMillis(FEED_L1_TTL_MS))
        .build();

    // Request coalescing: single flight per cache key
    private final ConcurrentHashMap<String, CompletableFuture<List<PostSummary>>> inflightRequests = new ConcurrentHashMap<>();

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public List<PostSummary> getNearbyFeed(double lat, double lng, double radiusKm, String cursor) {
        String geohash = GeohashUtils.encode(lat, lng, GEOHASH_PRECISION);
        String radiusBucket = FeedCacheKeys.radiusBucket(radiusKm);
        String cacheKey = FeedCacheKeys.nearbyKey(geohash, radiusBucket, cursor);

        // L1 fast path - heap objects
        List<PostSummary> l1Hit = l1Cache.getIfPresent(cacheKey);
        if (l1Hit != null) {
            return l1Hit;
        }

        // L1 serialized fast path - byte[] (deserialize)
        byte[] serialized = l1CacheSerialized.getIfPresent(cacheKey);
        if (serialized != null) {
            try {
                List<PostSummary> result = objectMapper.readValue(serialized,
                    new com.fasterxml.jackson.core.type.TypeReference<List<PostSummary>>() {});
                l1Cache.put(cacheKey, result); // Promote to heap cache
                return result;
            } catch (Exception ex) {
                log.debug("L1_DESERIALIZE_FAILED key={} error={}", cacheKey, ex.getMessage());
                l1CacheSerialized.invalidate(cacheKey);
            }
        }

        // L2 + request coalescing: single flight per cache key
        return inflightRequests.computeIfAbsent(cacheKey, k -> CompletableFuture.supplyAsync(() -> {
            try {
                RedisCacheService redisCacheService = redisCacheServiceProvider.getIfAvailable();
                if (redisCacheService == null) {
                    return loadFromDatabase(lat, lng, radiusKm, cursor);
                }

                List<PostSummary> result = redisCacheService.getListOrCompute(
                    k, PostSummary.class, FEED_L2_TTL_SECONDS,
                    () -> loadFromDatabase(lat, lng, radiusKm, cursor)
                );

                // Populate L1 caches
                l1Cache.put(k, result);
                try {
                    l1CacheSerialized.put(k, objectMapper.writeValueAsBytes(result));
                } catch (Exception ex) {
                    log.debug("L1_SERIALIZE_FAILED key={} error={}", k, ex.getMessage());
                }

                return result;
            } finally {
                inflightRequests.remove(k);
            }
        })).join(); // Block only this thread; others get same future
    }

    public List<PostSummary> getPostDetails(java.util.List<Long> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }

        String cacheKey = FeedCacheKeys.FEED_POST_DETAIL + ":batch:" + String.join(",", postIds.stream()
                .map(String::valueOf)
                .toList());

        // L1 check
        List<PostSummary> l1Hit = l1Cache.getIfPresent(cacheKey);
        if (l1Hit != null) {
            return l1Hit;
        }

        RedisCacheService redisCacheService = redisCacheServiceProvider.getIfAvailable();
        if (redisCacheService == null) {
            return loadPostsFromDatabase(postIds);
        }

        return redisCacheService.getListOrCompute(
                cacheKey,
                PostSummary.class,
                FeedCacheKeys.FEED_POST_TTL_SECONDS,
                () -> loadPostsFromDatabase(postIds)
        );
    }

    public void invalidateNearbyFeed(String geohash) {
        RedisCacheService redisCacheService = redisCacheServiceProvider.getIfAvailable();
        if (redisCacheService == null) {
            return;
        }
        String pattern = FeedCacheKeys.FEED_NEARBY + ":" + geohash + ":*";
        redisCacheService.deletePattern(pattern);
        
        // Also invalidate L1 caches matching the pattern
        l1Cache.asMap().keySet().removeIf(key -> key.startsWith(FeedCacheKeys.FEED_NEARBY + ":" + geohash));
        l1CacheSerialized.asMap().keySet().removeIf(key -> key.startsWith(FeedCacheKeys.FEED_NEARBY + ":" + geohash));
        
        log.debug("FEED_INVALIDATED geohash={}", geohash);
    }

    private List<PostSummary> loadFromDatabase(double lat, double lng, double radiusKm, String cursor) {
        double radiusMeters = radiusKm * 1000;
        int limit = DEFAULT_LIMIT;

        List<SocialPost> posts = postRepository.findActivePostsWithinRadius(
                lat, lng, radiusMeters, limit);

        return posts.stream()
                .limit(limit)
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    private List<PostSummary> loadPostsFromDatabase(java.util.List<Long> postIds) {
        return postRepository.findAllById(postIds).stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
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
