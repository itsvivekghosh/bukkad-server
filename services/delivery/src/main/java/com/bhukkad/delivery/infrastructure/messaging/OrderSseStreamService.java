package com.bhukkad.delivery.live;

import com.bhukkad.common.error.SseCapacityExceededException;
import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pod-local SSE registry for rider/customer/kitchen live streams.
 *
 * <p>Runtime copy of the realtime service's {@code OrderSseStreamServiceImpl};
 * semantics are intentionally identical (budgets, O(1) disconnect via the
 * emitter index, evict-on-dispatch-rejection, the same metric names). The two
 * copies were NOT merged into a shared helper: their update DTOs are different
 * classes ({@code delivery.dto.response.OrderLiveUpdate} vs
 * {@code realtime.dto.OrderLiveUpdate}) with no common type, so a shared
 * implementation would be new abstraction across service boundaries, not a
 * trivial extraction (batch note: align semantics — done — unify in PERF-5).</p>
 */
@Slf4j
@Service
public class OrderSseStreamService {

    private static final long DEFAULT_TIMEOUT = 300_000L;
    static final String METRIC_CONNECTIONS = "sse_connections";
    static final String METRIC_CAPACITY_REJECTED = "sse_capacity_rejected";

    private final OrderLiveReplayStore replayStore;
    private final Executor sseDispatchExecutor;
    private final MeterRegistry meterRegistry;

    public OrderSseStreamService(OrderLiveReplayStore replayStore,
                                 @Qualifier("sseDispatchExecutor") Executor sseDispatchExecutor,
                                 MeterRegistry meterRegistry) {
        this.replayStore = replayStore;
        this.sseDispatchExecutor = sseDispatchExecutor;
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge(METRIC_CONNECTIONS, totalEmitters, AtomicInteger::doubleValue);
        }
    }

    @Value("${app.live.sse.max-emitters-per-stream:50}")
    int maxEmittersPerStream = 50;

    @Value("${app.live.sse.max-total-emitters:2000}")
    int maxTotalEmitters = 2000;

    private final AtomicInteger totalEmitters = new AtomicInteger();

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> kitchenStreams = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> riderStreams = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> customerStreams = new ConcurrentHashMap<>();

    /**
     * emitter → (streams, key). Disconnect and send failures resolve through
     * this index in O(1); without it every dead socket triggered an O(N)
     * scan of all streams (audit V-06 finish).
     */
    private final Map<SseEmitter, StreamHandle> emitterIndex = new ConcurrentHashMap<>();

    private record StreamHandle(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams, Long key) {
    }

    private void recordCapacityRejected(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_CAPACITY_REJECTED, "reason", reason).increment();
        }
    }

    public SseEmitter subscribeKitchen(Long restaurantId, String lastEventId) {
        return subscribe(
                kitchenStreams,
                restaurantId,
                "kitchen",
                OrderLiveReplayStore.streamKeyKitchen(restaurantId),
                lastEventId,
                null);
    }

    public SseEmitter subscribeRider(Long agentId, String lastEventId) {
        return subscribe(
                riderStreams,
                agentId,
                "rider",
                OrderLiveReplayStore.streamKeyRider(agentId),
                lastEventId,
                null);
    }

    public SseEmitter subscribeCustomer(Long orderId, String lastEventId, Object snapshot) {
        return subscribe(
                customerStreams,
                orderId,
                "customer-order",
                OrderLiveReplayStore.streamKeyOrder(orderId),
                lastEventId,
                snapshot);
    }

    public void broadcastKitchen(Long restaurantId, OrderLiveUpdate update) {
        broadcast(kitchenStreams.get(restaurantId), update);
    }

    public void broadcastRider(Long agentId, OrderLiveUpdate update) {
        broadcast(riderStreams.get(agentId), update);
    }

    public void broadcastCustomer(Long orderId, OrderLiveUpdate update) {
        broadcast(customerStreams.get(orderId), update);
    }

    private SseEmitter subscribe(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams,
                                      Long key,
                                      String channel,
                                      String replayStreamKey,
                                      String lastEventId,
                                      Object snapshot) {
        reserveGlobalBudget(channel, key);
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        CopyOnWriteArrayList<SseEmitter> emitters =
                streams.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        synchronized (emitters) {
            if (emitters.size() >= maxEmittersPerStream) {
                totalEmitters.decrementAndGet();
                if (emitters.isEmpty()) {
                    streams.remove(key, emitters);
                }
                log.warn("SSE_CAPACITY_EXCEEDED | channel={} | id={} | current={} | max={}",
                        channel, key, emitters.size(), maxEmittersPerStream);
                recordCapacityRejected("stream");
                throw new SseCapacityExceededException(
                        "Stream capacity reached for " + channel + " " + key
                                + " (" + maxEmittersPerStream + " connections); retry shortly");
            }
            emitters.add(emitter);
        }
        emitterIndex.put(emitter, new StreamHandle(streams, key));

        Runnable cleanup = () -> remove(streams, key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .id("0")
                    .name("connected")
                    .data("{\"channel\":\"" + channel + "\",\"id\":" + key + "}"));

            replayMissedEvents(emitter, replayStreamKey, lastEventId);

            if (snapshot != null) {
                emitter.send(SseEmitter.event()
                        .id("snapshot")
                        .name("order-snapshot")
                        .data(snapshot));
            }
        } catch (IOException e) {
            cleanup.run();
            throw new IllegalStateException("Failed to open SSE stream", e);
        }

        log.debug("SSE subscribed | channel={} | id={} | lastEventId={}", channel, key, lastEventId);
        return emitter;
    }

    private void replayMissedEvents(SseEmitter emitter, String replayStreamKey, String lastEventId)
            throws IOException {
        if (!StringUtils.hasText(lastEventId) || !StringUtils.hasText(replayStreamKey)) {
            return;
        }
        long afterEventId = OrderLiveReplayStore.parseLastEventId(lastEventId);
        if (afterEventId < 0) {
            return;
        }
        List<OrderLiveUpdate> missed = replayStore.replayAfter(replayStreamKey, afterEventId);
        for (OrderLiveUpdate update : missed) {
            sendUpdate(emitter, update);
        }
        if (!missed.isEmpty()) {
            log.debug("SSE replayed {} events | stream={} | afterEventId={}",
                    missed.size(), replayStreamKey, afterEventId);
        }
    }

    private void broadcast(List<SseEmitter> emitters, OrderLiveUpdate update) {
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                sseDispatchExecutor.execute(() -> {
                    try {
                        sendUpdate(emitter, update);
                    } catch (Exception e) {
                        evictEmitter(emitter);
                        log.debug("SSE emitter removed after send failure: {}", e.getMessage());
                    }
                });
            } catch (RejectedExecutionException ex) {
                // Never run the write on the caller thread: a saturated pool
                // means the socket is behind, so drop it and count it.
                log.warn("SSE_DISPATCH_REJECTED | error={}", ex.getMessage());
                recordCapacityRejected("dispatch");
                evictEmitter(emitter);
            }
        }
    }

    private void evictEmitter(SseEmitter emitter) {
        removeFromAllStreams(emitter);
        removeAndCompleteEmitter(emitter);
    }

    private void sendUpdate(SseEmitter emitter, OrderLiveUpdate update) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event()
                .name("order-update")
                .data(update);
        if (update.getEventId() != null) {
            event.id(String.valueOf(update.getEventId()));
        }
        emitter.send(event);
    }

    private void removeFromAllStreams(SseEmitter emitter) {
        StreamHandle handle = emitterIndex.get(emitter);
        if (handle != null) {
            remove(handle.streams(), handle.key(), emitter);
            return;
        }
        // Emitter unknown to the index (defensive): fall back to the scan.
        remove(kitchenStreams, emitter);
        remove(riderStreams, emitter);
        remove(customerStreams, emitter);
    }

    private void sendHeartbeatToAll(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams) {
        for (CopyOnWriteArrayList<SseEmitter> emitters : streams.values()) {
            sendHeartbeat(emitters);
        }
    }

    private void remove(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams,
                         SseEmitter emitter) {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : streams.entrySet()) {
            remove(streams, entry.getKey(), emitter);
        }
    }

    private void reserveGlobalBudget(String channel, Long key) {
        int reserved = totalEmitters.incrementAndGet();
        if (reserved > maxTotalEmitters) {
            totalEmitters.decrementAndGet();
            log.warn("SSE_GLOBAL_BUDGET_EXCEEDED | channel={} | id={} | total={} | max={}",
                    channel, key, reserved, maxTotalEmitters);
            recordCapacityRejected("global");
            throw new SseCapacityExceededException(
                    "Server SSE connection budget reached (" + maxTotalEmitters + "); retry shortly");
        }
    }

    private void remove(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams,
                         Long key,
                         SseEmitter emitter) {
        emitterIndex.remove(emitter);
        CopyOnWriteArrayList<SseEmitter> emitters = streams.get(key);
        if (emitters != null) {
            if (emitters.remove(emitter)) {
                totalEmitters.decrementAndGet();
            }
            if (emitters.isEmpty()) {
                streams.remove(key, emitters);
            }
        }
    }

    private void removeAndCompleteEmitter(SseEmitter emitter) {
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }

    public void sendHeartbeats() {
        sendHeartbeatToAll(kitchenStreams);
        sendHeartbeatToAll(riderStreams);
        sendHeartbeatToAll(customerStreams);
    }

    public int activeConnectionCount() {
        return countStreams(kitchenStreams) + countStreams(riderStreams) + countStreams(customerStreams);
    }

    /** Index residency, observable for tests/maintenance assertions. */
    int indexedEmitterCount() {
        return emitterIndex.size();
    }

    private int countStreams(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams) {
        return streams.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }

    private void sendHeartbeat(List<SseEmitter> emitters) {
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                sseDispatchExecutor.execute(() -> {
                    try {
                        emitter.send(SseEmitter.event().comment("heartbeat"));
                    } catch (IOException | IllegalStateException e) {
                        evictEmitter(emitter);
                    }
                });
            } catch (RejectedExecutionException ex) {
                log.warn("SSE_HEARTBEAT_DISPATCH_REJECTED | error={}", ex.getMessage());
                recordCapacityRejected("dispatch");
                evictEmitter(emitter);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        closeAll(kitchenStreams);
        closeAll(riderStreams);
        closeAll(customerStreams);
        emitterIndex.clear();
        log.info("SSE streams closed for graceful shutdown");
    }

    private void closeAll(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams) {
        for (CopyOnWriteArrayList<SseEmitter> emitters : streams.values()) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            emitters.clear();
        }
        streams.clear();
    }

    public void broadcastKitchenLocal(Long restaurantId, com.bhukkad.delivery.dto.response.OrderLiveUpdate update) {
        broadcast(kitchenStreams.get(restaurantId), update);
    }

    public void broadcastRiderLocal(Long agentId, com.bhukkad.delivery.dto.response.OrderLiveUpdate update) {
        broadcast(riderStreams.get(agentId), update);
    }

    public void broadcastCustomerLocal(Long orderId, com.bhukkad.delivery.dto.response.OrderLiveUpdate update) {
        broadcast(customerStreams.get(orderId), update);
    }
}
