package com.bhukkad.common.loadtest;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple load testing utility for measuring API performance and throughput.
 * <p>
 * This utility can be used to simulate 200k+ TPS load on API endpoints to
 * validate system performance under heavy load. Uses {@link LoadTestHttpClient}
 * abstraction to avoid web framework dependencies.
 * </p>
 */
public class ApiLoadTester {

    private static final Logger log = LoggerFactory.getLogger(ApiLoadTester.class);

    private final LoadTestHttpClient httpClient;
    private final String baseUrl;
    private final int concurrentUsers;
    private final int rampUpPeriodSeconds;
    private final int testDurationSeconds;
    private final MeterRegistry meterRegistry;

    // Metrics for load testing
    private final AtomicLong requestCount = new AtomicLong(0);
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private final AtomicReference<Long> maxLatencyMs = new AtomicReference<>(0L);
    private final AtomicReference<Long> minLatencyMs = new AtomicReference<>(Long.MAX_VALUE);

    // Micrometer metrics
    private Counter requestTotalCounter;
    private Counter requestSuccessCounter;
    private Counter requestErrorCounter;
    private Timer latencyTimer;

    public ApiLoadTester(LoadTestHttpClient httpClient, String baseUrl, int concurrentUsers,
                         int rampUpPeriodSeconds, int testDurationSeconds, MeterRegistry meterRegistry) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.concurrentUsers = concurrentUsers;
        this.rampUpPeriodSeconds = rampUpPeriodSeconds;
        this.testDurationSeconds = testDurationSeconds;
        this.meterRegistry = meterRegistry;

        // Initialize load test metrics
        if (meterRegistry != null) {
            this.requestTotalCounter = Counter.builder("bhukkad.loadtest.requests_total")
                    .description("Total number of requests made during load test")
                    .register(meterRegistry);
            this.requestSuccessCounter = Counter.builder("bhukkad.loadtest.requests_success")
                    .description("Number of successful requests during load test")
                    .register(meterRegistry);
            this.requestErrorCounter = Counter.builder("bhukkad.loadtest.requests_errors")
                    .description("Number of failed requests during load test")
                    .register(meterRegistry);
            this.latencyTimer = Timer.builder("bhukkad.loadtest.latency")
                    .description("Request latency during load test")
                    .register(meterRegistry);
        }
    }

    /**
     * Runs a load test against the specified endpoint.
     * @param endpoint The API endpoint to test (relative to baseUrl)
     * @param httpMethod The HTTP method to use
     * @param requestBody The request body (can be null for GET requests)
     * @param responseType The expected response type
     * @return Load test results
     */
    public LoadTestResult runLoadTest(String endpoint, HttpMethod httpMethod, Object requestBody,
                                      Class<?> responseType) throws InterruptedException {
        log.info("Starting load test: {} {} {} users, {}s ramp-up, {}s duration",
                httpMethod, baseUrl + endpoint, concurrentUsers, rampUpPeriodSeconds, testDurationSeconds);

        // Reset metrics
        requestCount.set(0);
        successCount.set(0);
        errorCount.set(0);
        totalLatencyMs.set(0);
        maxLatencyMs.set(0L);
        minLatencyMs.set(Long.MAX_VALUE);

        ExecutorService executorService = Executors.newFixedThreadPool(concurrentUsers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrentUsers);

        long startTime = System.currentTimeMillis();

        // Submit user tasks with ramp-up period
        long userIntervalMs = rampUpPeriodSeconds > 0 ? 
                (rampUpPeriodSeconds * 1000L) / concurrentUsers : 0;

        for (int i = 0; i < concurrentUsers; i++) {
            final int userId = i;
            Future<?> future = executorService.submit(() -> {
                try {
                    // Stagger user start times for ramp-up
                    if (userIntervalMs > 0) {
                        Thread.sleep(userId * userIntervalMs);
                    }
                    startLatch.await(); // Wait for all users to be ready

                    long userEndTime = System.currentTimeMillis() + (testDurationSeconds * 1000L);
                    while (System.currentTimeMillis() < userEndTime) {
                        long requestStart = System.currentTimeMillis();
                        try {
                            // Make the HTTP request
                            URI uri = URI.create(baseUrl + endpoint);
                            Object response;
                            
                            switch (httpMethod) {
                                case GET:
                                    response = httpClient.get(uri, responseType);
                                    break;
                                case POST:
                                    response = httpClient.post(uri, requestBody, responseType);
                                    break;
                                case PUT:
                                    httpClient.put(uri, requestBody);
                                    response = null;
                                    break;
                                case DELETE:
                                    httpClient.delete(uri);
                                    response = null;
                                    break;
                                default:
                                    throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
                            }

                            long requestLatency = System.currentTimeMillis() - requestStart;
                            recordRequestSuccess(requestLatency);
                        } catch (Exception ex) {
                            long requestLatency = System.currentTimeMillis() - requestStart;
                            recordRequestError(requestLatency);
                            // Log error occasionally to avoid flooding logs
                            if (errorCount.get() % 1000 == 0) {
                                log.warn("Request error (user {}): {}", userId, ex.getMessage());
                            }
                        }
                        
                        // Small delay between requests to prevent overwhelming
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Start all users
        startLatch.countDown();

        // Wait for all users to complete
        doneLatch.await();
        executorService.shutdown();
        executorService.awaitTermination(30, TimeUnit.SECONDS);

        long endTime = System.currentTimeMillis();
        long testDurationMs = endTime - startTime;

        // Calculate and log results
        double actualDurationSec = testDurationMs / 1000.0;
        double throughputTps = successCount.get() / actualDurationSec;
        double avgLatencyMs = successCount.get() > 0 ? 
                (double) totalLatencyMs.get() / successCount.get() : 0;

        log.info("Load test completed:");
        log.info("  Duration: {}s", actualDurationSec);
        log.info("  Total requests: {}", requestCount.get());
        log.info("  Successful requests: {}", successCount.get());
        log.info("  Failed requests: {}", errorCount.get());
        log.info("  Success rate: {}%", 
                requestCount.get() > 0 ? String.format("%.2f", 100.0 * successCount.get() / requestCount.get()) : "0");
        log.info("  Throughput: {:.2f} TPS", throughputTps);
        log.info("  Average latency: {:.2f} ms", avgLatencyMs);
        log.info("  Max latency: {} ms", maxLatencyMs.get());
        log.info("  Min latency: {} ms", 
                minLatencyMs.get() == Long.MAX_VALUE ? 0 : minLatencyMs.get());

        // Update Micrometer metrics
        if (meterRegistry != null) {
            requestTotalCounter.increment(requestCount.get());
            requestSuccessCounter.increment(successCount.get());
            requestErrorCounter.increment(errorCount.get());
        }

        return new LoadTestResult(
                requestCount.get(),
                successCount.get(),
                errorCount.get(),
                throughputTps,
                avgLatencyMs,
                maxLatencyMs.get(),
                minLatencyMs.get() == Long.MAX_VALUE ? 0 : minLatencyMs.get(),
                Duration.ofMillis(testDurationMs)
        );
    }

    private void recordRequestSuccess(long latencyMs) {
        requestCount.incrementAndGet();
        successCount.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        maxLatencyMs.updateAndGet(current -> Math.max(current, latencyMs));
        minLatencyMs.updateAndGet(current -> Math.min(current, latencyMs));
        if (latencyTimer != null) {
            latencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
        }
    }

    private void recordRequestError(long latencyMs) {
        requestCount.incrementAndGet();
        errorCount.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        maxLatencyMs.updateAndGet(current -> Math.max(current, latencyMs));
        minLatencyMs.updateAndGet(current -> Math.min(current, latencyMs));
    }

    /**
     * HTTP methods supported by the load tester.
     */
    public enum HttpMethod {
        GET, POST, PUT, DELETE
    }

    /**
     * Load test results.
     */
    public static class LoadTestResult {
        private final long totalRequests;
        private final long successfulRequests;
        private final long failedRequests;
        private final double throughputTps;
        private final double averageLatencyMs;
        private final long maxLatencyMs;
        private final long minLatencyMs;
        private final Duration duration;

        public LoadTestResult(long totalRequests, long successfulRequests, long failedRequests,
                              double throughputTps, double averageLatencyMs, long maxLatencyMs,
                              long minLatencyMs, Duration duration) {
            this.totalRequests = totalRequests;
            this.successfulRequests = successfulRequests;
            this.failedRequests = failedRequests;
            this.throughputTps = throughputTps;
            this.averageLatencyMs = averageLatencyMs;
            this.maxLatencyMs = maxLatencyMs;
            this.minLatencyMs = minLatencyMs;
            this.duration = duration;
        }

        public long getTotalRequests() { return totalRequests; }
        public long getSuccessfulRequests() { return successfulRequests; }
        public long getFailedRequests() { return failedRequests; }
        public double getThroughputTps() { return throughputTps; }
        public double getAverageLatencyMs() { return averageLatencyMs; }
        public long getMaxLatencyMs() { return maxLatencyMs; }
        public long getMinLatencyMs() { return minLatencyMs; }
        public Duration getDuration() { return duration; }

        @Override
        public String toString() {
            return String.format(
                    "LoadTestResult{totalRequests=%d, successful=%d, failed=%d, throughput=%.2f TPS, " +
                            "avgLatency=%.2fms, maxLatency=%dms, minLatency=%dms, duration=%s}",
                    totalRequests, successfulRequests, failedRequests, throughputTps,
                    averageLatencyMs, maxLatencyMs, minLatencyMs, duration);
        }
    }
}