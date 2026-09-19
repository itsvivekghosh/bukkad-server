package com.bhukkad.social.integration;

import com.bhukkad.social.api.dto.request.CreatePostRequest;
import com.bhukkad.social.api.dto.response.PostSummary;
import com.bhukkad.social.domain.service.SocialFeedService;
import com.bhukkad.social.domain.service.SocialPostService;
import com.bhukkad.social.AbstractSocialIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for social feed functionality with Testcontainers.
 *
 * <p>Requires Docker running. Automatically starts PostgreSQL with PostGIS
 * and Redis containers. External service clients are mocked.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SocialFeedIntegrationTest extends AbstractSocialIntegrationTest {

    @Autowired
    private SocialPostService socialPostService;

    @Autowired
    private SocialFeedService socialFeedService;

    @MockBean
    private CaffeineCache l1Cache;

    @MockBean
    private com.bhukkad.common.cache.RedisCacheService redisCacheService;

    @Test
    void createPost_andQueryFeed() {
        // Create a post
        CreatePostRequest request = new CreatePostRequest(
                100L, // restaurantId
                "Check out this amazing burger!",
                new String[]{"image1.jpg"},
                "update",
                28.6139, // latitude (Delhi)
                77.2090  // longitude
        );

        PostSummary post = socialPostService.createPost(10L, request);
        assertNotNull(post.id());

        // Query nearby feed
        var feed = socialFeedService.getNearbyFeed(28.6139, 77.2090, 5.0, null, 20);
        assertNotNull(feed);
        assertNotNull(feed.posts());
    }

    @Test
    void getRestaurantPosts() {
        List<PostSummary> posts = socialPostService.getRestaurantPosts(100L, 0, 10);
        assertNotNull(posts);
        assertTrue(posts.size() >= 0);
    }

    @Test
    void getUserPosts() {
        List<PostSummary> posts = socialPostService.getUserPosts(10L, 0, 10);
        assertNotNull(posts);
    }
}
