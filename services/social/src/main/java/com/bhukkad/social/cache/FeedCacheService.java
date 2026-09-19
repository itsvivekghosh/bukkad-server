package com.bhukkad.social.cache;

import com.bhukkad.common.cache.FeedCacheKeys;
import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.common.util.GeohashUtils;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.entity.SocialPost;
import com.bhukkad.social.domain.repository.SocialPostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class FeedCacheService {

    private final SocialPostRepository postRepository;
    private final ObjectProvider<RedisCacheService> redisCacheServiceProvider;

    private static final int GEOHASH_PRECISION = 4;
    private static final long FEED_L1_TTL_SECONDS = 30;
    private static final long FEED_L2_TTL_SECONDS = 600;
    private static final int DEFAULT_LIMIT = 100;

    public List<PostSummary> getNearbyFeed(double lat, double lng, double radiusKm, String cursor) {
        String geohash = GeohashUtils.encode(lat, lng, GEOHASH_PRECISION);
        String radiusBucket = FeedCacheKeys.radiusBucket(radiusKm);
        String cacheKey = FeedCacheKeys.nearbyKey(geohash, radiusBucket, cursor);

        RedisCacheService redisCacheService = redisCacheServiceProvider.getIfAvailable();
        if (redisCacheService == null) {
            return loadFromDatabase(lat, lng, radiusKm, cursor);
        }

        return redisCacheService.getListOrCompute(
                cacheKey,
                PostSummary.class,
                FEED_L2_TTL_SECONDS,
                () -> loadFromDatabase(lat, lng, radiusKm, cursor)
        );
    }

    public List<PostSummary> getPostDetails(java.util.List<Long> postIds) {
        if (postIds.isEmpty()) {
            return List.of();
        }

        String cacheKey = FeedCacheKeys.FEED_POST_DETAIL + ":batch:" + String.join(",", postIds.stream()
                .map(String::valueOf)
                .toList());

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
        log.debug("FEED_INVALIDATED geohash={}", geohash);
    }

    private List<PostSummary> loadFromDatabase(double lat, double lng, double radiusKm, String cursor) {
        double radiusMeters = radiusKm * 1000;
        int limit = 100;

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
