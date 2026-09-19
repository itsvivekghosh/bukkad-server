package com.bhukkad.social.domain.service;

import com.bhukkad.social.domain.entity.SocialPost;
import com.bhukkad.social.domain.repository.SocialPostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Async batch sync job for likes.
 *
 * <p>Reads like events from the Redis queue and batch-syncs them to
 * PostgreSQL. Runs on a fixed schedule to amortize DB writes.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LikeSyncService {

    private static final String LIKE_QUEUE_KEY = "social:like:queue";
    private static final int BATCH_SYNC_SIZE = 1000;
    private static final long LIKE_QUEUE_TTL_SECONDS = TimeUnit.HOURS.toSeconds(1);
    private static final long LIKE_QUEUE_MAX_SIZE = 500_000;

    private static final String INSERT_LIKE_SQL = """
            INSERT INTO post_likes (post_id, user_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (post_id, user_id) DO NOTHING
            """;

    private static final String DELETE_LIKE_SQL = """
            DELETE FROM post_likes WHERE post_id = ? AND user_id = ?
            """;

    private static final String UPDATE_LIKE_COUNT_SQL = """
            UPDATE social_posts
            SET like_count = ?, updated_at = ?
            WHERE id = ?
            """;

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final SocialPostRepository postRepository;
    private final LikeCounterManager likeCounterManager;

    /**
     * Batch sync likes from Redis queue to PostgreSQL using jdbcTemplate.batchUpdate.
     */
    @SchedulerLock(name = "likeBatchSync", lockAtMostFor = "PT30S", lockAtLeastFor = "PT5S")
    @Scheduled(fixedRateString = "${app.like.sync-interval-ms:1000}")
    @Transactional
    public void batchSyncLikes() {
        try {
            long queueSize = redisTemplate.opsForList().size(LIKE_QUEUE_KEY);
            if (queueSize == 0) {
                return;
            }

            int batchSize = (int) Math.min(queueSize, BATCH_SYNC_SIZE);
            List<String> events = redisTemplate.opsForList().range(LIKE_QUEUE_KEY, 0, batchSize - 1);
            if (events == null || events.isEmpty()) {
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            List<Object[]> likeBatch = new ArrayList<>(batchSize);
            List<Object[]> unlikeBatch = new ArrayList<>(batchSize);
            Set<Long> likedPostIds = new java.util.HashSet<>();

            for (String event : events) {
                try {
                    String[] parts = event.split(":");
                    if (parts.length != 4) {
                        continue;
                    }
                    Long postId = Long.parseLong(parts[0]);
                    Long userId = Long.parseLong(parts[1]);
                    boolean liked = "1".equals(parts[2]);

                    if (liked) {
                        likeBatch.add(new Object[]{postId, userId, now});
                        likedPostIds.add(postId);
                    } else {
                        unlikeBatch.add(new Object[]{postId, userId});
                    }
                } catch (Exception ex) {
                    log.warn("LIKE_SYNC_PARSE_FAILED event={} error={}", event, ex.getMessage());
                }
            }

            if (!likeBatch.isEmpty()) {
                jdbcTemplate.batchUpdate(INSERT_LIKE_SQL, likeBatch);
            }
            if (!unlikeBatch.isEmpty()) {
                jdbcTemplate.batchUpdate(DELETE_LIKE_SQL, unlikeBatch);
            }

            // Update denormalized like_count in social_posts for liked posts incrementally
            if (!likedPostIds.isEmpty()) {
                List<SocialPost> postsToUpdate = postRepository.findAllById(likedPostIds);
                List<Object[]> countUpdates = new ArrayList<>();
                for (SocialPost post : postsToUpdate) {
                    Long count = likeCounterManager.getCount(post.getId());
                    if (count != null && !count.equals(post.getLikeCount())) {
                        countUpdates.add(new Object[]{count, now, post.getId()});
                    }
                }
                if (!countUpdates.isEmpty()) {
                    jdbcTemplate.batchUpdate(UPDATE_LIKE_COUNT_SQL, countUpdates);
                }
            }

            // Remove processed events from queue
            redisTemplate.opsForList().trim(LIKE_QUEUE_KEY, batchSize, -1);
            redisTemplate.expire(LIKE_QUEUE_KEY, LIKE_QUEUE_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.error("LIKE_BATCH_SYNC_FAILED error={}", ex.getMessage(), ex);
        }
    }
}
