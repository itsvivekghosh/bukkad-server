package com.bhukkad.social.load;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Load test for social service endpoints.
 *
 * <p>Simulates concurrent requests to measure throughput and latency.
 * Target metrics (from SOCIAL_COMMERCE_IMPLEMENTATION.md):
 * <ul>
 *   <li>Feed: 50K TPS with p95 < 200ms</li>
 *   <li>Likes: 100K TPS with p95 < 50ms</li>
 *   <li>Orders: 10K TPS with p95 < 500ms</li>
 * </ul>
 *
 * <p>Note: For production load testing, use Gatling or k6 simulations.
 * This test provides a basic multi-threaded validation.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SocialLoadTest extends AbstractSocialIntegrationTest {

    @Autowired
    private SocialPostService socialPostService;

    @Autowired
    private SocialFeedService socialFeedService;

    @MockBean
    private com.bhukkad.common.cache.RedisCacheService redisCacheService;

    @MockBean
    private org.springframework.cache.caffeine.CaffeineCache caffeineCache;

    private static final int THREADS = 50;
    private static final int REQUESTS_PER_THREAD = 100;
    private static final int TOTAL_REQUESTS = THREADS * REQUESTS_PER_THREAD;

    @Test
    void feed_load_test() throws InterruptedException {
        // Create test posts first
        for (int i = 0; i < 100; i++) {
            CreatePostRequest request = new CreatePostRequest(
                    100L + (i % 10),
                    "Load test post " + i,
                    new String[]{},
                    "update",
                    28.6139 + (Math.random() * 0.1),
                    77.2090 + (Math.random() * 0.1)
            );
            socialPostService.createPost(10L + (i % 50), request);
        }

        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch latch = new CountDownLatch(TOTAL_REQUESTS);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Long> latencies = new ArrayList<>(TOTAL_REQUESTS);

        long startTime = System.nanoTime();

        for (int t = 0; t < THREADS; t++) {
            final int threadId = t;
            executor.submit(() -> {
                for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    long reqStart = System.nanoTime();
                    try {
                        double lat = 28.6139 + (Math.random() * 0.1);
                        double lng = 77.2090 + (Math.random() * 0.1);
                        var feed = socialFeedService.getNearbyFeed(lat, lng, 5.0, null, 20);
                        if (feed != null && feed.posts() != null) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        long reqEnd = System.nanoTime();
                        latencies.add((reqEnd - reqStart) / 1_000_000);
                        latch.countDown();
                    }
                }
            });
        }

        boolean finished = latch.await(60, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

        executor.shutdown();

        System.out.println("=== Feed Load Test Results ===");
        System.out.println("Total requests: " + TOTAL_REQUESTS);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Total time: " + totalTimeMs + "ms");
        System.out.println("Throughput: " + String.format("%.2f", (double) successCount.get() / (totalTimeMs / 1000.0)) + " req/s");

        if (!latencies.isEmpty()) {
            latencies.sort(Long::compareTo);
            double p50 = latencies.get((int) (latencies.size() * 0.5));
            double p95 = latencies.get((int) (latencies.size() * 0.95));
            double p99 = latencies.get((int) (latencies.size() * 0.99));
            System.out.println("p50 latency: " + p50 + "ms");
            System.out.println("p95 latency: " + p95 + "ms");
            System.out.println("p99 latency: " + p99 + "ms");
        }
        System.out.println("===============================");

        assertTrue(finished, "Load test did not complete within timeout");
        assertTrue(successCount.get() > TOTAL_REQUESTS * 0.95,
                "Success rate too low: " + successCount.get() + "/" + TOTAL_REQUESTS);
    }
}
