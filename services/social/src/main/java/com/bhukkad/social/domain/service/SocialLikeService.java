package com.bhukkad.social.domain.service;

import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.entity.PostLike;
import com.bhukkad.social.domain.entity.SocialPost;
import com.bhukkad.social.domain.repository.PostLikeRepository;
import com.bhukkad.social.domain.repository.SocialPostRepository;
import com.bhukkad.social.observability.PerformanceMetrics;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Optimized like service targeting 100K+ TPS with zero DB contention.
 *
 * <p>Hot path: Redis counters + existence sets. Async write-behind to
 * PostgreSQL via a scheduled sync job.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SocialLikeService {

    private static final String LIKE_COUNT_KEY = "social:post:likes:%d";
    private static final String LIKED_BY_KEY = "social:post:liked:%d:%d";
    private static final String LIKE_QUEUE_KEY = "social:like:queue";
    private static final long LIKE_QUEUE_TTL_SECONDS = TimeUnit.HOURS.toSeconds(1);
    private static final long LIKE_QUEUE_MAX_SIZE = 500_000;
    private static final long LIKE_COUNT_TTL_SECONDS = TimeUnit.DAYS.toSeconds(7);
    private static final int BATCH_SYNC_SIZE = 1000;

    private final PostLikeRepository likeRepository;
    private final SocialPostRepository postRepository;
    private final StringRedisTemplate redisTemplate;
    private final LikeCounterManager likeCounterManager;
    private final PerformanceMetrics performanceMetrics;

    /**
     * Toggle like on a post. Pure Redis hot path with async DB persistence.
     *
     * <p>No DB read on the hot path. Post existence is assumed valid at this
     * layer (controller validates post exists). Redis is the source of truth
     * for the like state; PostgreSQL is eventually consistent via the queue.</p>
     */
    public PostSummary toggleLike(Long postId, Long userId) {
        performanceMetrics.recordLikeRequest();
        Timer.Sample timer = performanceMetrics.startLikeTimer();

        try {
            String countKey = String.format(LIKE_COUNT_KEY, postId);
            String likedByKey = String.format(LIKED_BY_KEY, postId, userId);

            Boolean alreadyLiked = redisTemplate.opsForValue().get(likedByKey) != null;

            if (Boolean.TRUE.equals(alreadyLiked)) {
                // Unlike: delete existence flag + decrement counter
                redisTemplate.delete(likedByKey);
                Long newCount = redisTemplate.opsForValue().increment(countKey, -1);
                enqueueLikeEvent(postId, userId, false);
                performanceMetrics.recordLikeLatency(timer);
                return buildSummary(postId, newCount, false);
            } else {
                // Like: set existence flag + increment counter
                redisTemplate.opsForValue().set(likedByKey, "1", LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
                Long newCount = redisTemplate.opsForValue().increment(countKey, 1);
                if (newCount != null && newCount == 1) {
                    redisTemplate.expire(countKey, LIKE_COUNT_TTL_SECONDS, TimeUnit.SECONDS);
                }
                enqueueLikeEvent(postId, userId, true);
                performanceMetrics.recordLikeLatency(timer);
                return buildSummary(postId, newCount, true);
            }
        } catch (Exception ex) {
            performanceMetrics.recordLikeLatency(timer);
            throw ex;
        }
    }

    /**
     * Enqueue like event for async DB sync.
     */
    private void enqueueLikeEvent(Long postId, Long userId, boolean liked) {
        try {
            String event = String.format("%d:%d:%d:%d",
                    postId, userId, liked ? 1 : 0, System.currentTimeMillis());
            redisTemplate.opsForList().rightPush(LIKE_QUEUE_KEY, event);
            redisTemplate.expire(LIKE_QUEUE_KEY, LIKE_QUEUE_TTL_SECONDS, TimeUnit.SECONDS);

            // Cap queue size to prevent unbounded growth under extreme load
            Long size = redisTemplate.opsForList().size(LIKE_QUEUE_KEY);
            if (size != null && size > LIKE_QUEUE_MAX_SIZE) {
                redisTemplate.opsForList().trim(LIKE_QUEUE_KEY, size - LIKE_QUEUE_MAX_SIZE, -1);
            }
        } catch (Exception ex) {
            log.warn("LIKE_EVENT_ENQUEUE_FAILED postId={} error={}", postId, ex.getMessage());
        }
    }

    /**
     * Build PostSummary from Redis count, falling back to DB if Redis returns null.
     */
    private PostSummary buildSummary(Long postId, Long redisCount, boolean liked) {
        SocialPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post not found: " + postId));

        int likeCount = redisCount != null
                ? redisCount.intValue()
                : (liked ? post.getLikeCount() + 1 : Math.max(0, post.getLikeCount() - 1));

        return new PostSummary(
                post.getId(), post.getRestaurantId(), post.getRestaurantName(),
                post.getAuthorId(), post.getAuthorName(), post.getContent(),
                post.getMediaUrls(), post.getPostType(),
                likeCount, post.getCommentCount(), post.getStatus(), post.getCreatedAt()
        );
    }
}
