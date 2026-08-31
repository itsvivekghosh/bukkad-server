package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.bhukkad.exception.SseCapacityExceededException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;

@Slf4j
@Service
public class OrderSseStreamService {

    private static final long DEFAULT_TIMEOUT = 300_000L; // 5 minutes — reduces reconnect churn vs 60s

    private final OrderLiveReplayStore replayStore;
    private final Executor sseDispatchExecutor;

    public OrderSseStreamService(OrderLiveReplayStore replayStore,
                                 @Qualifier("sseDispatchExecutor") Executor sseDispatchExecutor) {
        this.replayStore = replayStore;
        this.sseDispatchExecutor = sseDispatchExecutor;
    }

    /**
     * Maximum concurrent emitters per stream key (an order, a restaurant's
     * kitchen, or a rider). Bounds per-stream pod memory: a flood of guests
     * reconnecting to one order cannot exceed this. New subscribers beyond the
     * cap are rejected with {@link SseCapacityExceededException} (mapped to 503)
     * instead of degrading existing subscribers.
     */
    @Value("${app.live.sse.max-emitters-per-stream:50}")
    int maxEmittersPerStream = 50;

    /**
     * Fleet-wide per-pod cap on concurrent SSE connections across all streams.
     * Every connection holds a servlet async context + buffers, so this bounds
     * total pod memory against connection floods regardless of stream spread.
     */
    @Value("${app.live.sse.max-total-emitters:2000}")
    int maxTotalEmitters = 2000;

    private final AtomicInteger totalEmitters = new AtomicInteger();

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> kitchenStreams = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> riderStreams = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> customerStreams = new ConcurrentHashMap<>();

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
        // Check-then-add inside the list monitor so concurrent subscriptions
        // cannot exceed the per-stream cap. Subscriptions are infrequent, so
        // the brief serialization is immaterial.
        synchronized (emitters) {
            if (emitters.size() >= maxEmittersPerStream) {
                totalEmitters.decrementAndGet();
                log.warn("SSE_CAPACITY_EXCEEDED | channel={} | id={} | current={} | max={}",
                        channel, key, emitters.size(), maxEmittersPerStream);
                throw new SseCapacityExceededException(
                        "Stream capacity reached for " + channel + " " + key
                                + " (" + maxEmittersPerStream + " connections); retry shortly");
            }
            emitters.add(emitter);
        }

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
                        removeFromAllStreams(emitter);
                        removeAndCompleteEmitter(emitter);
                        log.debug("SSE emitter removed after send failure: {}", e.getMessage());
                    }
                });
            } catch (Exception ex) {
                // Executor rejected (should not happen with CallerRunsPolicy, but degrade)
                log.warn("SSE dispatch rejected, falling back to inline send | error={}", ex.getMessage());
                try {
                    sendUpdate(emitter, update);
                } catch (Exception e) {
                    removeFromAllStreams(emitter);
                    removeAndCompleteEmitter(emitter);
                }
            }
        }
    }

    /** Removes a dead emitter from all three stream registries. */
    private void removeFromAllStreams(SseEmitter emitter) {
        remove(kitchenStreams, emitter);
        remove(riderStreams, emitter);
        remove(customerStreams, emitter);
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

    private void remove(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams,
                         SseEmitter emitter) {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : streams.entrySet()) {
            remove(streams, entry.getKey(), emitter);
        }
    }

    /**
     * Reserves one slot in the per-pod connection budget. The budget counts
     * every live connection across all streams; when it is exhausted new
     * subscribers are rejected (503) so a connection flood can never exhaust
     * pod memory regardless of how the load is spread across streams.
     */
    private void reserveGlobalBudget(String channel, Long key) {
        int reserved = totalEmitters.incrementAndGet();
        if (reserved > maxTotalEmitters) {
            totalEmitters.decrementAndGet();
            log.warn("SSE_GLOBAL_BUDGET_EXCEEDED | channel={} | id={} | total={} | max={}",
                    channel, key, reserved, maxTotalEmitters);
            throw new SseCapacityExceededException(
                    "Server SSE connection budget reached (" + maxTotalEmitters + "); retry shortly");
        }
    }

    private void remove(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams,
                         Long key,
                         SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = streams.get(key);
        if (emitters != null) {
            // Decrement the global budget only if this emitter was actually
            // present, so double-invoked cleanup (onError + onCompletion) cannot
            // drive the counter negative.
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
                // emitter may already be closed
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

    private int countStreams(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams) {
        return streams.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }

    private void sendHeartbeatToAll(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams) {
        for (CopyOnWriteArrayList<SseEmitter> emitters : streams.values()) {
            sendHeartbeat(emitters);
        }
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
                        removeFromAllStreams(emitter);
                        removeAndCompleteEmitter(emitter);
                    }
                });
            } catch (Exception ex) {
                // CallerRuns fallback — inline
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (IOException | IllegalStateException e) {
                    removeFromAllStreams(emitter);
                    removeAndCompleteEmitter(emitter);
                }
            }
        }
    }

    @PreDestroy
    void shutdown() {
        closeAll(kitchenStreams);
        closeAll(riderStreams);
        closeAll(customerStreams);
        log.info("SSE streams closed for graceful shutdown");
    }

    private void closeAll(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams) {
        for (CopyOnWriteArrayList<SseEmitter> emitters : streams.values()) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // emitter may already be closed
                }
            }
            emitters.clear();
        }
        streams.clear();
    }

    /** Internal broadcast to kitchen listeners only (no Redis publish). */
    public void broadcastKitchenLocal(Long restaurantId, com.bhukkad.dto.response.OrderLiveUpdate update) {
        broadcast(kitchenStreams.get(restaurantId), update);
    }

    /** Internal broadcast to rider listeners only (no Redis publish). */
    public void broadcastRiderLocal(Long agentId, com.bhukkad.dto.response.OrderLiveUpdate update) {
        broadcast(riderStreams.get(agentId), update);
    }

    /** Internal broadcast to customer listeners only (no Redis publish). */
    public void broadcastCustomerLocal(Long orderId, com.bhukkad.dto.response.OrderLiveUpdate update) {
        broadcast(customerStreams.get(orderId), update);
    }
}