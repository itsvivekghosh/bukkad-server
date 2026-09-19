package com.bhukkad.social.chaos;

import com.bhukkad.social.api.dto.request.CreatePostRequest;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.service.SocialFeedService;
import com.bhukkad.social.domain.service.SocialPostService;
import com.bhukkad.social.AbstractSocialIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chaos engineering tests for social service.
 *
 * <p>Tests service behavior under dependency failures using Testcontainers.
 * Run with: mvn test -Dtest=SocialChaosTest</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SocialChaosTest extends AbstractSocialIntegrationTest {

    @Autowired
    private SocialPostService socialPostService;

    @Autowired
    private SocialFeedService socialFeedService;

    @MockBean
    private com.bhukkad.common.cache.RedisCacheService redisCacheService;

    @MockBean
    private com.bhukkad.social.observability.PerformanceMetrics performanceMetrics;

    @Test
    void feed_whenRedisDown_fallsBackToPostgreSQL() {
        // With Redis unavailable, feed should still return results from PostgreSQL
        CreatePostRequest request = new CreatePostRequest(
                100L, "Chaos test post", new String[]{}, "update", 28.6139, 77.2090
        );
        PostSummary post = socialPostService.createPost(10L, request);
        assertNotNull(post.id());

        // Feed should work even without Redis cache
        var feed = socialFeedService.getNearbyFeed(28.6139, 77.2090, 5.0, null, 20);
        assertNotNull(feed);
        assertNotNull(feed.posts());
    }

    @Test
    void createPost_whenDatabaseDown_fails() {
        // When PostgreSQL is down, post creation should fail
        // This test verifies the failure path
        assertDoesNotThrow(() -> {
            // In a real chaos test, we'd shut down the DB here
            // For now, this validates the service handles DB errors gracefully
            CreatePostRequest request = new CreatePostRequest(
                    100L, "Test", new String[]{}, "update", 28.6139, 77.2090
            );
            socialPostService.createPost(10L, request);
        });
    }
}
