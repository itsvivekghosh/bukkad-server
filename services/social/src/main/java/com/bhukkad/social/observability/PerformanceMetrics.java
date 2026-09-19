package com.bhukkad.social.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom performance metrics for the social service.
 *
 * <p>Documented in SOCIAL_COMMERCE_IMPLEMENTATION.md Phase 5.</p>
 */
@Component
public class PerformanceMetrics {

    public static final String FEED_REQUESTS = "social.feed.requests";
    public static final String FEED_LATENCY = "social.feed.latency";
    public static final String FEED_ERRORS = "social.feed.errors";
    public static final String LIKE_REQUESTS = "social.like.requests";
    public static final String LIKE_LATENCY = "social.like.latency";
    public static final String ORDER_REQUESTS = "social.order.requests";
    public static final String ORDER_LATENCY = "social.order.latency";
    public static final String REDIS_HIT_RATIO = "social.redis.hit_ratio";
    public static final String CACHE_INVALIDATIONS = "social.cache.invalidations";

    private final Counter feedRequests;
    private final Timer feedLatency;
    private final Counter feedErrors;
    private final Counter likeRequests;
    private final Timer likeLatency;
    private final Counter orderRequests;
    private final Timer orderLatency;
    private final Counter cacheInvalidations;

    public PerformanceMetrics(MeterRegistry registry) {
        this.feedRequests = Counter.builder(FEED_REQUESTS)
                .description("Total feed requests")
                .register(registry);
        this.feedLatency = Timer.builder(FEED_LATENCY)
                .description("Feed request latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.feedErrors = Counter.builder(FEED_ERRORS)
                .description("Feed request errors")
                .register(registry);
        this.likeRequests = Counter.builder(LIKE_REQUESTS)
                .description("Total like toggle requests")
                .register(registry);
        this.likeLatency = Timer.builder(LIKE_LATENCY)
                .description("Like toggle latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.orderRequests = Counter.builder(ORDER_REQUESTS)
                .description("Total order-from-post requests")
                .register(registry);
        this.orderLatency = Timer.builder(ORDER_LATENCY)
                .description("Order-from-post latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.cacheInvalidations = Counter.builder(CACHE_INVALIDATIONS)
                .description("Cache invalidations")
                .register(registry);
    }

    public void recordFeedRequest() {
        feedRequests.increment();
    }

    public Timer.Sample startFeedTimer() {
        return Timer.start();
    }

    public void recordFeedLatency(Timer.Sample sample) {
        sample.stop(feedLatency);
    }

    public void recordFeedError() {
        feedErrors.increment();
    }

    public void recordLikeRequest() {
        likeRequests.increment();
    }

    public Timer.Sample startLikeTimer() {
        return Timer.start();
    }

    public void recordLikeLatency(Timer.Sample sample) {
        sample.stop(likeLatency);
    }

    public void recordOrderRequest() {
        orderRequests.increment();
    }

    public Timer.Sample startOrderTimer() {
        return Timer.start();
    }

    public void recordOrderLatency(Timer.Sample sample) {
        sample.stop(orderLatency);
    }

    public void recordCacheInvalidation() {
        cacheInvalidations.increment();
    }
}
