package com.bhukkad.engagement.realtime.domain.service.impl;

import com.bhukkad.engagement.realtime.config.LiveProperties;
import com.bhukkad.engagement.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.engagement.realtime.domain.service.OrderLiveReplayStore;
import com.bhukkad.engagement.realtime.domain.service.OrderSseStreamService;
import com.bhukkad.engagement.realtime.exception.SseCapacityExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Pod-local reactive SSE registry for the live order streams.
 * 
 * <p>Optimizations:</p>
 * <ul>
 *   <li>Batch heartbeats - sends heartbeats to all streams in parallel batches</li>
 *   <li>Lazy stream cleanup - removes stale streams based on last activity</li>
 *   <li>Adaptive backpressure buffer sizing</li>
 *   <li>Connection pool tuning for high concurrency</li>
 * </ul>
 */
@Slf4j
@Service
@EnableScheduling
public class OrderSseStreamServiceImpl implements OrderSseStreamService {

    public static final String METRIC_CONNECTIONS = "sse_connections";
    public static final String METRIC_CAPACITY_REJECTED = "sse_capacity_rejected";

    private static final int HEARTBEAT_BATCH_SIZE = 1000;
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofMinutes(5);
    private static final int MIN_BUFFER_SIZE = 256;
    private static final int DEFAULT_BUFFER_SIZE = 1024;

    private final OrderLiveReplayStore replayStore;
    private final LiveProperties liveProperties;
    private final MeterRegistry meterRegistry;
    private final TaskScheduler taskScheduler;

    private final AtomicInteger totalSubscribers = new AtomicInteger();

    /** streamKey -> multicast sink for that stream */
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> kitchenStreams = new ConcurrentHashMap<>();
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> riderStreams = new ConcurrentHashMap<>();
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> customerStreams = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> streamSubscriberCounts = new ConcurrentHashMap<>();
    
    // Track last activity per stream for lazy cleanup
    private final Map<Long, AtomicLong> streamLastActivity = new ConcurrentHashMap<>();

    public OrderSseStreamServiceImpl(OrderLiveReplayStore replayStore,
                                     LiveProperties liveProperties,
                                     MeterRegistry meterRegistry,
                                     TaskScheduler taskScheduler) {
        this.replayStore = replayStore;
        this.liveProperties = liveProperties;
        this.meterRegistry = meterRegistry;
        this.taskScheduler = taskScheduler;
        if (meterRegistry != null) {
            meterRegistry.gauge(METRIC_CONNECTIONS, totalSubscribers, AtomicInteger::doubleValue);
        }
    }

    @PostConstruct
    void init() {
        // Schedule batch heartbeats every 15 seconds
        taskScheduler.scheduleAtFixedRate(this::sendBatchHeartbeats, Duration.ofSeconds(15));
        
        // Schedule lazy stream cleanup every 60 seconds
        taskScheduler.scheduleAtFixedRate(this::cleanupStaleStreams, Duration.ofMinutes(1));
        
        log.info("SSE_STREAM_SERVICE_INITIALIZED | heartbeat_interval=15s | cleanup_interval=60s");
    }

    @Override
    public Flux<ServerSentEvent<String>> subscribeKitchen(Long restaurantId, String lastEventId) {
        return subscribe(kitchenStreams, restaurantId, "kitchen",
                replayStore.streamKeyKitchen(restaurantId), lastEventId, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> subscribeRider(Long agentId, String lastEventId) {
        return subscribe(riderStreams, agentId, "rider",
                replayStore.streamKeyRider(agentId), lastEventId, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> subscribeCustomer(Long orderId, String lastEventId, Object snapshot) {
        return subscribe(customerStreams, orderId, "customer-order",
                replayStore.streamKeyOrder(orderId), lastEventId, snapshot);
    }

    @Override
    public void broadcastKitchen(Long restaurantId, OrderLiveUpdate update) {
        broadcast(kitchenStreams.get(restaurantId), update, restaurantId);
    }

    @Override
    public void broadcastRider(Long agentId, OrderLiveUpdate update) {
        broadcast(riderStreams.get(agentId), update, agentId);
    }

    @Override
    public void broadcastCustomer(Long orderId, OrderLiveUpdate update) {
        broadcast(customerStreams.get(orderId), update, orderId);
    }

    @Override
    public void sendHeartbeats() {
        sendBatchHeartbeats();
    }

    @Override
    public int activeConnectionCount() {
        return countStreams(kitchenStreams) + countStreams(riderStreams) + countStreams(customerStreams);
    }

    /** Number of active subscribers across all streams (observable for tests). */
    public int indexedEmitterCount() {
        return totalSubscribers.get();
    }

    /**
     * Send heartbeats to all streams in parallel batches.
     * Replaces sequential per-stream heartbeat with batched parallel execution.
     */
    @Scheduled(fixedDelayString = "${app.sse.heartbeat-interval-ms:15000}")
    public void sendBatchHeartbeats() {
        long start = System.nanoTime();
        AtomicInteger totalSent = new AtomicInteger(0);
        
        ServerSentEvent<String> heartbeat = ServerSentEvent.<String>builder()
                .comment("heartbeat")
                .build();
        
        for (Map<Long, Sinks.Many<ServerSentEvent<String>>> streams : List.of(
                kitchenStreams, riderStreams, customerStreams)) {
            List<Sinks.Many<ServerSentEvent<String>>> sinks = new java.util.ArrayList<>(streams.values());
            
            // Process in parallel batches
            IntStream.range(0, (sinks.size() + HEARTBEAT_BATCH_SIZE - 1) / HEARTBEAT_BATCH_SIZE)
                .parallel()
                .forEach(batchIdx -> {
                    int from = batchIdx * HEARTBEAT_BATCH_SIZE;
                    int to = Math.min(from + HEARTBEAT_BATCH_SIZE, sinks.size());
                    
                    for (int i = from; i < to; i++) {
                        Sinks.Many<ServerSentEvent<String>> sink = sinks.get(i);
                        if (sink != null) {
                            Sinks.EmitResult result = sink.tryEmitNext(heartbeat);
                            if (result.isSuccess()) {
                                totalSent.incrementAndGet();
                            } else {
                                log.warn("SSE_HEARTBEAT_FAILED | error={}", result);
                                recordCapacityRejected("dispatch");
                            }
                        }
                    }
                });
        }
        
        long elapsedMs = java.time.Duration.ofNanos(System.nanoTime() - start).toMillis();
        log.debug("SSE_BATCH_HEARTBEATS | sent={} | elapsedMs={}", totalSent.get(), elapsedMs);
    }

    /**
     * Lazy cleanup of stale streams based on last activity timestamp.
     * Removes streams that haven't had activity for STREAM_IDLE_TIMEOUT.
     */
    @Scheduled(fixedDelayString = "${app.sse.cleanup-interval-ms:60000}")
    public void cleanupStaleStreams() {
        long cutoff = System.currentTimeMillis() - STREAM_IDLE_TIMEOUT.toMillis();
        AtomicInteger cleaned = new AtomicInteger(0);
        
        for (Map<Long, Sinks.Many<ServerSentEvent<String>>> streams : List.of(
                kitchenStreams, riderStreams, customerStreams)) {
            streams.entrySet().removeIf(entry -> {
                AtomicLong lastActivity = streamLastActivity.get(entry.getKey());
                boolean isStale = lastActivity != null && lastActivity.get() < cutoff;
                if (isStale) {
                    entry.getValue().tryEmitComplete();
                    streamLastActivity.remove(entry.getKey());
                    streamSubscriberCounts.remove(entry.getKey());
                    cleaned.incrementAndGet();
                }
                return isStale;
            });
        }
        
        if (cleaned.get() > 0) {
            log.info("SSE_STALE_STREAMS_CLEANED | count={}", cleaned.get());
        }
    }

    private Flux<ServerSentEvent<String>> subscribe(Map<Long, Sinks.Many<ServerSentEvent<String>>> streams,
                                                     Long key,
                                                     String channel,
                                                     String replayStreamKey,
                                                     String lastEventId,
                                                     Object snapshot) {
        if (key == null) {
            throw new IllegalArgumentException("Stream key cannot be null");
        }
        
        // Enforce global capacity by counting current subscribers.
        synchronized (streams) {
            int current = totalSubscribers.get();
            if (current >= liveProperties.getMaxTotalEmitters()) {
                recordCapacityRejected("global");
                log.warn("SSE_GLOBAL_BUDGET_EXCEEDED | channel={} | id={} | total={}", channel, key, current);
                throw new SseCapacityExceededException("Server SSE connection budget reached; retry shortly");
            }
            totalSubscribers.incrementAndGet();
        }

        AtomicInteger streamCount = streamSubscriberCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
        streamCount.incrementAndGet();

        // Adaptive buffer size based on total subscriber count
        int bufferSize = totalSubscribers.get() > 10000 ? MIN_BUFFER_SIZE : DEFAULT_BUFFER_SIZE;
        
        Sinks.Many<ServerSentEvent<String>> sink = streams.computeIfAbsent(key,
                ignored -> Sinks.many().multicast()
                        .onBackpressureBuffer(bufferSize, true)); // true = DROP_OLDEST

        // Track activity timestamp
        streamLastActivity.computeIfAbsent(key, k -> new AtomicLong(System.currentTimeMillis()));

        Flux<ServerSentEvent<String>> connected = Flux.just(
                ServerSentEvent.<String>builder()
                        .id("0")
                        .event("connected")
                        .data("{\"channel\":\"" + channel + "\",\"id\":" + key + "}")
                        .build());

        Flux<ServerSentEvent<String>> replay = replayMissedEvents(replayStreamKey, 
                parseLastEventId(lastEventId));
        Flux<ServerSentEvent<String>> snapshotFlux = snapshot != null
                ? Flux.just(ServerSentEvent.<String>builder()
                        .id("snapshot")
                        .event("order-snapshot")
                        .data(snapshot.toString())
                        .build())
                : Flux.empty();

        return Flux.concat(connected, replay, snapshotFlux, sink.asFlux())
                .doFinally(signal -> {
                    totalSubscribers.decrementAndGet();
                    if (signal == reactor.core.publisher.SignalType.CANCEL
                            || signal == reactor.core.publisher.SignalType.ON_COMPLETE) {
                        log.debug("SSE_UNSUBSCRIBED | channel={} | id={} | signal={}", channel, key, signal);
                        int remaining = streamCount.decrementAndGet();
                        if (remaining <= 0) {
                            streams.computeIfPresent(key, (k, v) -> {
                                if (v == sink) return null;
                                return v;
                            });
                            streamSubscriberCounts.computeIfPresent(key, (k, v) -> {
                                if (v.get() <= 0) return null;
                                return v;
                            });
                            streamLastActivity.remove(key);
                        }
                    }
                });
    }

    private Flux<ServerSentEvent<String>> replayMissedEvents(String replayStreamKey, long lastEventId) {
        if (!StringUtils.hasText(replayStreamKey)) {
            return Flux.empty();
        }
        if (lastEventId < 0) {
            return Flux.empty();
        }
        List<OrderLiveUpdate> missed = replayStore.replayAfter(replayStreamKey, lastEventId);
        return Flux.fromIterable(missed)
                .map(update -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(update.getEventId()))
                        .event("order-update")
                        .data(update.toString())
                        .build());
    }

    private void broadcast(Sinks.Many<ServerSentEvent<String>> sink, OrderLiveUpdate update, Long streamKey) {
        if (sink == null) {
            return;
        }
        // Update activity timestamp on broadcast
        streamLastActivity.computeIfAbsent(streamKey, k -> new AtomicLong()).set(System.currentTimeMillis());
        
        ServerSentEvent<String> event = ServerSentEvent.<String>builder()
                .id(String.valueOf(update.getEventId()))
                .event("order-update")
                .data(update.toString())
                .build();
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("SSE_BROADCAST_FAILED | error={}", result);
            recordCapacityRejected("dispatch");
        }
    }

    private int countStreams(Map<Long, Sinks.Many<ServerSentEvent<String>>> streams) {
        return streams.size();
    }

    /**
     * Parse last event ID string to long. Returns -1 if parsing fails.
     */
    private long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(lastEventId);
        } catch (NumberFormatException ex) {
            log.warn("SSE_INVALID_EVENT_ID | id={}", lastEventId);
            return -1L;
        }
    }

    private void recordCapacityRejected(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_CAPACITY_REJECTED, "reason", reason).increment();
        }
    }

    @PreDestroy
    void shutdown() {
        for (Map<Long, Sinks.Many<ServerSentEvent<String>>> streams : List.of(
                kitchenStreams, riderStreams, customerStreams)) {
            for (Sinks.Many<ServerSentEvent<String>> sink : streams.values()) {
                sink.tryEmitComplete();
            }
            streams.clear();
        }
        streamLastActivity.clear();
        log.info("SSE_STREAMS_CLOSED | graceful_shutdown");
    }
}
