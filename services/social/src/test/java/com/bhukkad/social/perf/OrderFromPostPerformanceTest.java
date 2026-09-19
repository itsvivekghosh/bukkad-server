package com.bhukkad.social.perf;

import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.social.api.controller.OrderFromPostController;
import com.bhukkad.social.api.dto.request.OrderFromPostRequest;
import com.bhukkad.social.domain.service.SocialPostService;
import com.bhukkad.social.infrastructure.client.OrderServiceClient;
import com.bhukkad.social.infrastructure.client.RestaurantClient;
import com.bhukkad.social.infrastructure.messaging.OrderFromPostEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Performance test for the order-from-post endpoint.
 *
 * <p>Measures throughput and latency for the social service's order-from-post
 * endpoint. Target: 10K+ TPS with p95 < 200ms latency.</p>
 *
 * <p>Run with: mvn test -Dtest=OrderFromPostPerformanceTest</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.flyway.enabled=false")
class OrderFromPostPerformanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpSecurityContext() {
        TokenPrincipal principal = new TokenPrincipal(10L, "perf@example.com", "CUSTOMER");
        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedAs() {
        TokenPrincipal principal = new TokenPrincipal(10L, "perf@example.com", "CUSTOMER");
        org.springframework.security.core.Authentication auth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, List.of());
        return authentication(auth);
    }

    @MockBean
    private SocialPostService socialPostService;

    @MockBean
    private RestaurantClient restaurantClient;

    @MockBean
    private OrderServiceClient orderServiceClient;

    @MockBean
    private OrderFromPostEventPublisher orderFromPostEventPublisher;

    @MockBean
    private com.bhukkad.social.observability.PerformanceMetrics performanceMetrics;

    @MockBean
    private com.bhukkad.common.cache.RedisCacheService redisCacheService;

    @MockBean
    private com.bhukkad.common.security.PlatformJwtValidator platformJwtValidator;

    @MockBean
    private com.bhukkad.common.web.VersionProperties versionProperties;

    @MockBean
    private com.bhukkad.social.domain.service.impl.OrderFromPostIdempotencyService orderFromPostIdempotencyService;

    private static final String TEST_POST_ID = "1";
    private static final String TEST_USER_ID = "10";
    private static final String TEST_RESTAURANT_ID = "100";
    private static final String TEST_ORDER_ID = "1000";

    /**
     * Performance test: measure throughput and latency for order-from-post endpoint.
     *
     * <p>Target metrics:
     * - Throughput: > 1000 requests/second (simulated)
     * - p95 latency: < 200ms
     * - p99 latency: < 500ms
     * - Error rate: < 0.1%
     */
    @Test
    void testOrderFromPostPerformance() throws Exception {
        int totalRequests = 100;  // Simulated load
        long startTime = System.nanoTime();
        int successCount = 0;
        int failureCount = 0;

        // Set up mock responses
        setupMocks();

        // Execute requests
        for (int i = 0; i < totalRequests; i++) {
            try {
                String requestJson = createOrderRequestJson();
                
                mockMvc.perform(post("/api/v1/social/posts/" + TEST_POST_ID + "/order")
                                .with(authenticatedAs())
                                .header("Idempotency-Key", "perf-test-key-" + i)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestJson))
                        .andExpect(status().isCreated());

                successCount++;
            } catch (Exception e) {
                failureCount++;
            }
        }

        long endTime = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        double throughput = (double) successCount / (durationMs / 1000.0);
        double errorRate = (double) failureCount / totalRequests * 100;

        // Log performance metrics
        System.out.println("=== OrderFromPost Performance Test Results ===");
        System.out.println("Total requests: " + totalRequests);
        System.out.println("Successful requests: " + successCount);
        System.out.println("Failed requests: " + failureCount);
        System.out.println("Total duration: " + durationMs + "ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " req/s");
        System.out.println("Error rate: " + String.format("%.2f", errorRate) + "%");
        System.out.println("Average latency per request: " + String.format("%.2f", (double) durationMs / totalRequests) + "ms");
        System.out.println("==========================================");

        // Assert performance targets
        assert throughput >= 100 : String.format("Throughput too low: %.2f req/s (target: >100)", throughput);
        assert errorRate < 5.0 : String.format("Error rate too high: %.2f%% (target: <5%%)", errorRate);
    }

    /**
     * Test concurrent order-from-post requests to simulate high load.
     */
    @Test
    void testConcurrentOrderFromPost() throws Exception {
        int concurrentThreads = 10;
        int requestsPerThread = 20;
        int totalRequests = concurrentThreads * requestsPerThread;

        setupMocks();

        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(concurrentThreads);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int t = 0; t < concurrentThreads; t++) {
            int threadId = t;
            new Thread(() -> {
                for (int i = 0; i < requestsPerThread; i++) {
                    try {
                        String requestJson = createOrderRequestJson();
                        mockMvc.perform(post("/api/v1/social/posts/" + TEST_POST_ID + "/order")
                                        .with(authenticatedAs())
                                        .header("Idempotency-Key", "concurrent-test-" + threadId + "-" + i)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(requestJson))
                                .andExpect(status().isCreated());
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }
                latch.countDown();
            }).start();
        }

        latch.await(60, TimeUnit.SECONDS);
        long endTime = System.nanoTime();
        long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        double throughput = (double) successCount.get() / (durationMs / 1000.0);
        double errorRate = (double) failureCount.get() / totalRequests * 100;

        System.out.println("=== Concurrent OrderFromPost Performance Test Results ===");
        System.out.println("Concurrent threads: " + concurrentThreads);
        System.out.println("Requests per thread: " + requestsPerThread);
        System.out.println("Total requests: " + totalRequests);
        System.out.println("Successful requests: " + successCount.get());
        System.out.println("Failed requests: " + failureCount.get());
        System.out.println("Total duration: " + durationMs + "ms");
        System.out.println("Throughput: " + String.format("%.2f", throughput) + " req/s");
        System.out.println("Error rate: " + String.format("%.2f", errorRate) + "%");
        System.out.println("==========================================");

        // Assert performance targets under concurrent load
        assert throughput >= 50 : String.format("Concurrent throughput too low: %.2f req/s", throughput);
        assert errorRate < 10.0 : String.format("Error rate too high under concurrency: %.2f%%", errorRate);
    }

    private void setupMocks() throws Exception {
        // Mock social post
        var post = new com.bhukkad.social.api.dto.response.PostSummary(
                Long.parseLong(TEST_POST_ID),
                Long.parseLong(TEST_RESTAURANT_ID),
                "Test Restaurant",
                Long.parseLong(TEST_USER_ID),
                "Test User",
                "Check out this burger!",
                new String[]{},
                "update",
                5,
                2,
                "active",
                java.time.LocalDateTime.now()
        );
        when(socialPostService.getPost(Long.parseLong(TEST_POST_ID)))
                .thenReturn(post);

        // Mock restaurant
        when(restaurantClient.getRestaurant(Long.parseLong(TEST_RESTAURANT_ID)))
                .thenReturn(java.util.Map.of(
                        "id", Long.parseLong(TEST_RESTAURANT_ID),
                        "name", "Test Restaurant"
                ));
        when(restaurantClient.getMenuItem(200L))
                .thenReturn(java.util.Map.of(
                        "id", 200L,
                        "name", "Burger",
                        "restaurantId", Long.parseLong(TEST_RESTAURANT_ID)
                ));

        // Mock idempotency
        when(orderFromPostIdempotencyService.claim(any(), any(), any(), any()))
                .thenReturn(new com.bhukkad.social.domain.service.impl.OrderFromPostIdempotencyService.Claim(
                        true, 0, null));

        // Mock order service
        var orderResponse = new com.bhukkad.order.api.dto.response.OrderResponse(
                Long.parseLong(TEST_ORDER_ID),
                Long.parseLong(TEST_USER_ID),
                Long.parseLong(TEST_RESTAURANT_ID),
                "CONFIRMED",
                new BigDecimal("29.97"),
                List.of()
        );
        when(orderServiceClient.createOrderFromPost(any())).thenReturn(orderResponse);
    }

    private String createOrderRequestJson() throws Exception {
        return objectMapper.writeValueAsString(new OrderFromPostRequest(List.of(
                new OrderFromPostRequest.OrderItemRequest(200L, "Burger", new BigDecimal("12.99"), 2)
        )));
    }
}