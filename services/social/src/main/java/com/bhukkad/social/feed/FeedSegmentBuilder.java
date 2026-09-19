package com.bhukkad.social.feed;

import com.bhukkad.common.cache.FeedCacheKeys;
import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.common.util.GeohashUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bhukkad.social.domain.entity.SocialPost;
import com.bhukkad.social.domain.repository.SocialPostRepository;
import com.bhukkad.social.util.BloomFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Feed segment builder with bounded parallelism and active cell limits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FeedSegmentBuilder {

    private static final int GEOHASH_PRECISION = 4;
    private static final int POSTS_PER_SEGMENT = 200;
    private static final String ACTIVE_CELLS_KEY = "feed:active:cells";
    private static final int MAX_ACTIVE_CELLS = 500;
    private static final int REBUILD_PARALLELISM = 8;

    private final SocialPostRepository postRepository;
    private final ObjectProvider<RedisTemplate<String, String>> redisTemplateProvider;
    private final ObjectProvider<RedisCacheService> redisCacheServiceProvider;
    private final ObjectMapper objectMapper;
    private final Executor rebuildExecutor = Executors.newFixedThreadPool(REBUILD_PARALLELISM, r -> {
        Thread t = new Thread(r, "feed-segment-rebuild");
        t.setDaemon(true);
        return t;
    });

    @SchedulerLock(name = "feedSegmentRebuild", lockAtMostFor = "25s")
    @Scheduled(fixedRateString = "${app.feed.segment-rebuild-ms:30000}")
    public void rebuildAllSegments() {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }

        try {
            Set<String> activeCells = redisTemplate.opsForSet().members(ACTIVE_CELLS_KEY);
            if (activeCells == null || activeCells.isEmpty()) {
                activeCells = Set.of("tdrd", "tdr3", "tdr9");
            } else if (activeCells.size() > MAX_ACTIVE_CELLS) {
                // Cap active cells to prevent unbounded growth
                List<String> capped = new ArrayList<>(activeCells).subList(0, MAX_ACTIVE_CELLS);
                activeCells = new java.util.LinkedHashSet<>(capped);
            }

            List<Future<?>> futures = new ArrayList<>(activeCells.size());
            for (String cell : activeCells) {
                futures.add(((java.util.concurrent.ExecutorService) rebuildExecutor)
                        .submit(() -> rebuildSegment(cell)));
            }

            for (Future<?> future : futures) {
                try {
                    future.get(20, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    log.warn("FEED_SEGMENT_REBUILD_FUTURE_FAILED error={}", ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.warn("FEED_SEGMENT_REBUILD_FAILED error={}", ex.getMessage());
        }
    }

    void rebuildSegment(String geohash) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }

        try {
            double[] bounds = GeohashUtils.decode(geohash);
            List<SocialPost> posts = postRepository.findActivePostsInBounds(
                    bounds[0], // southLat (minLat)
                    bounds[2], // westLng  (minLng)
                    bounds[1], // northLat (maxLat)
                    bounds[3], // eastLng  (maxLng)
                    POSTS_PER_SEGMENT);

            if (posts.isEmpty()) {
                return;
            }

            String feedKey = FeedCacheKeys.FEED_NEARBY + ":ids:" + geohash + ":5";
            redisTemplate.delete(feedKey);

            ZSetOperations<String, String> zSetOps = redisTemplate.opsForZSet();
            for (SocialPost post : posts) {
                zSetOps.add(feedKey, post.getId().toString(),
                        post.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toEpochSecond());
            }
            redisTemplate.expire(feedKey, 2, java.util.concurrent.TimeUnit.HOURS);

            cachePostDetails(posts);
            updateBloomFilter(geohash, posts);
            redisTemplate.opsForSet().add(ACTIVE_CELLS_KEY, geohash);
            redisTemplate.expire(ACTIVE_CELLS_KEY, 24, java.util.concurrent.TimeUnit.HOURS);

            log.debug("FEED_SEGMENT_REBUILT geohash={} posts={}", geohash, posts.size());
        } catch (Exception ex) {
            log.warn("FEED_SEGMENT_REBUILD_FAILED geohash={} error={}", geohash, ex.getMessage());
        }
    }

    public void trackActiveCell(double lat, double lng) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        String geohash = GeohashUtils.encode(lat, lng, GEOHASH_PRECISION);
        redisTemplate.opsForSet().add(ACTIVE_CELLS_KEY, geohash);
        redisTemplate.expire(ACTIVE_CELLS_KEY, 24, java.util.concurrent.TimeUnit.HOURS);
    }

    private void cachePostDetails(List<SocialPost> posts) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }
        RedisCacheService redisCacheService = redisCacheServiceProvider.getIfAvailable();
        if (redisCacheService == null) {
            return;
        }

        for (SocialPost post : posts) {
            String key = FeedCacheKeys.FEED_POST_DETAIL + ":" + post.getId();
            try {
                String json = objectMapper.writeValueAsString(new com.bhukkad.social.api.dto.response.PostSummary(
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
                ));
                redisTemplate.opsForValue().set(key, json, 60, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ex) {
                log.debug("FEED_POST_CACHE_FAILED postId={} error={}", post.getId(), ex.getMessage());
            }
        }
    }

    private void updateBloomFilter(String geohash, List<SocialPost> posts) {
        RedisTemplate<String, String> redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return;
        }

        String bloomKey = FeedCacheKeys.FEED_POST_DETAIL + ":bloom:" + geohash;
        BloomFilter filter = BloomFilter.build(
                posts.stream().map(SocialPost::getId).toList());
        try {
            redisTemplate.opsForValue().set(bloomKey, filter.toBase64(), 1, java.util.concurrent.TimeUnit.HOURS);
        } catch (Exception ex) {
            log.debug("BLOOM_FILTER_UPDATE_FAILED geohash={} error={}", geohash, ex.getMessage());
        }
    }
}
