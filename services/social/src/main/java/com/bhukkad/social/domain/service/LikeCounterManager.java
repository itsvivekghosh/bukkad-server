package com.bhukkad.social.domain.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis Hash-based like counter manager.
 *
 * <p>Maintains a centralized hash of post like counts for efficient
 * batch operations and memory usage. Unlike individual keys per post,
 * this uses a single Redis Hash to store all like counters.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikeCounterManager {

    private static final String LIKE_COUNTERS_HASH = "social:likes:counters";
    private static final long LIKE_COUNTERS_TTL_DAYS = 7;

    private final StringRedisTemplate redisTemplate;

    /**
     * Increment like counter for a post.
     *
     * @param postId the post ID
     * @param delta the increment value (positive for like, negative for unlike)
     * @return the new count, or null if the key does not exist
     */
    public Long increment(Long postId, long delta) {
        Long result = redisTemplate.opsForHash().increment(LIKE_COUNTERS_HASH,
                String.valueOf(postId), delta);
        redisTemplate.expire(LIKE_COUNTERS_HASH, LIKE_COUNTERS_TTL_DAYS, TimeUnit.DAYS);
        return result;
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
}
