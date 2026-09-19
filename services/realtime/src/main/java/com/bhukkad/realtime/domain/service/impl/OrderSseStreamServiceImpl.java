package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveReplayStore;
import com.bhukkad.realtime.domain.service.OrderSseStreamService;
import com.bhukkad.realtime.exception.SseCapacityExceededException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pod-local reactive SSE registry for the live order streams.
 *
 * <p>Uses {@link Sinks.Many} to multicast {@link ServerSentEvent} fluxes to
 * subscribers. This removes the servlet-thread-per-stream ceiling: the
 * underlying Netty runtime handles backpressure and concurrency without
 * holding a Tomcat thread for each connection.</p>
 *
 * <p>Metric contract: {@code sse_connections} gauge = active subscribers
 * across all streams (Prometheus: {@code sse_connections});
 * {@code sse_capacity_rejected{reason=stream|global|dispatch}} counter
 * (Prometheus: {@code sse_capacity_rejected_total}).</p>
 */
@Slf4j
@Service
public class OrderSseStreamServiceImpl implements OrderSseStreamService {

    public static final String METRIC_CONNECTIONS = "sse_connections";
    public static final String METRIC_CAPACITY_REJECTED = "sse_capacity_rejected";

    private final OrderLiveReplayStore replayStore;
    private final LiveProperties liveProperties;
    private final MeterRegistry meterRegistry;

    private final AtomicInteger totalSubscribers = new AtomicInteger();

    /** streamKey -> multicast sink for that stream */
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> kitchenStreams = new ConcurrentHashMap<>();
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> riderStreams = new ConcurrentHashMap<>();
    private final Map<Long, Sinks.Many<ServerSentEvent<String>>> customerStreams = new ConcurrentHashMap<>();
    private final Map<Long, AtomicInteger> streamSubscriberCounts = new ConcurrentHashMap<>();

    public OrderSseStreamServiceImpl(OrderLiveReplayStore replayStore,
                                     LiveProperties liveProperties,
                                     MeterRegistry meterRegistry) {
        this.replayStore = replayStore;
        this.liveProperties = liveProperties;
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge(METRIC_CONNECTIONS, totalSubscribers, AtomicInteger::doubleValue);
        }
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
        broadcast(kitchenStreams.get(restaurantId), update);
    }

    @Override
    public void broadcastRider(Long agentId, OrderLiveUpdate update) {
        broadcast(riderStreams.get(agentId), update);
    }

    @Override
    public void broadcastCustomer(Long orderId, OrderLiveUpdate update) {
        broadcast(customerStreams.get(orderId), update);
    }

    @Override
    public void sendHeartbeats() {
        sendHeartbeatToAll(kitchenStreams);
        sendHeartbeatToAll(riderStreams);
        sendHeartbeatToAll(customerStreams);
    }

    @Override
    public int activeConnectionCount() {
        return countStreams(kitchenStreams) + countStreams(riderStreams) + countStreams(customerStreams);
    }

    /** Number of active subscribers across all streams (observable for tests). */
    public int indexedEmitterCount() {
        return totalSubscribers.get();
    }

    private Flux<ServerSentEvent<String>> subscribe(Map<Long, Sinks.Many<ServerSentEvent<String>>> streams,
                                                    Long key,
                                                    String channel,
                                                    String replayStreamKey,
                                                    String lastEventId,
                                                    Object snapshot) {
        // Enforce global capacity by counting current subscribers.
        // Sinks.Many subscriber count is not directly exposed, so we track it
        // via totalSubscribers as a budget guard (approximation: each Flux.just
        // below adds one subscriber).
        synchronized (streams) {
            int current = totalSubscribers.get();
            if (current >= liveProperties.getMaxTotalEmitters()) {
                totalSubscribers.decrementAndGet();
                recordCapacityRejected("global");
                log.warn("SSE_GLOBAL_BUDGET_EXCEEDED | channel={} | id={} | total={}", channel, key, current);
                throw new SseCapacityExceededException("Server SSE connection budget reached; retry shortly");
            }
            totalSubscribers.incrementAndGet();
        }

        AtomicInteger streamCount = streamSubscriberCounts.computeIfAbsent(key, k -> new AtomicInteger(0));
        streamCount.incrementAndGet();

        Sinks.Many<ServerSentEvent<String>> sink = streams.computeIfAbsent(key,
                ignored -> Sinks.many().multicast().onBackpressureBuffer(1024));

        Flux<ServerSentEvent<String>> connected = Flux.just(
                ServerSentEvent.<String>builder()
                        .id("0")
                        .event("connected")
                        .data("{\"channel\":\"" + channel + "\",\"id\":" + key + "}")
                        .build());

        Flux<ServerSentEvent<String>> replay = replayMissedEvents(replayStreamKey, lastEventId);
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
                        log.debug("SSE unsubscribed | channel={} | id={} | signal={}", channel, key, signal);
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
                        }
                    }
                });
    }

    private Flux<ServerSentEvent<String>> replayMissedEvents(String replayStreamKey, String lastEventId) {
        if (!StringUtils.hasText(lastEventId) || !StringUtils.hasText(replayStreamKey)) {
            return Flux.empty();
        }
        long afterEventId = replayStore.parseLastEventId(lastEventId);
        if (afterEventId < 0) {
            return Flux.empty();
        }
        List<OrderLiveUpdate> missed = replayStore.replayAfter(replayStreamKey, afterEventId);
        return Flux.fromIterable(missed)
                .map(update -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(update.getEventId()))
                        .event("order-update")
                        .data(update.toString())
                        .build());
    }

    private void broadcast(Sinks.Many<ServerSentEvent<String>> sink, OrderLiveUpdate update) {
        if (sink == null) {
            return;
        }
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

    private void sendHeartbeatToAll(Map<Long, Sinks.Many<ServerSentEvent<String>>> streams) {
        for (Sinks.Many<ServerSentEvent<String>> sink : streams.values()) {
            sendHeartbeat(sink);
        }
    }

    private void sendHeartbeat(Sinks.Many<ServerSentEvent<String>> sink) {
        if (sink == null) {
            return;
        }
        ServerSentEvent<String> heartbeat = ServerSentEvent.<String>builder()
                .comment("heartbeat")
                .build();
        Sinks.EmitResult result = sink.tryEmitNext(heartbeat);
        if (result.isFailure()) {
            log.warn("SSE_HEARTBEAT_FAILED | error={}", result);
            recordCapacityRejected("dispatch");
        }
    }

    private int countStreams(Map<Long, Sinks.Many<ServerSentEvent<String>>> streams) {
        return streams.size();
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
        log.info("SSE streams closed for graceful shutdown");
    }
}
