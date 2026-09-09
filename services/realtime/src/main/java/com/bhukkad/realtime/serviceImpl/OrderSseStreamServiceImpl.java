package com.bhukkad.realtime.serviceImpl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.bhukkad.realtime.service.OrderLiveReplayStore;
import com.bhukkad.realtime.service.OrderSseStreamService;
import com.bhukkad.realtime.exception.SseCapacityExceededException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSseStreamServiceImpl implements OrderSseStreamService {

    private static final long DEFAULT_TIMEOUT = 300_000L;

    private final OrderLiveReplayStore replayStore;
    private final Executor sseDispatchExecutor;
    private final LiveProperties liveProperties;

    private final AtomicInteger totalEmitters = new AtomicInteger();

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> kitchenStreams = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> riderStreams = new ConcurrentHashMap<>();
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> customerStreams = new ConcurrentHashMap<>();

    @Override
    public SseEmitter subscribeKitchen(Long restaurantId, String lastEventId) {
        return subscribe(
                kitchenStreams,
                restaurantId,
                "kitchen",
                replayStore.streamKeyKitchen(restaurantId),
                lastEventId,
                null);
    }

    @Override
    public SseEmitter subscribeRider(Long agentId, String lastEventId) {
        return subscribe(
                riderStreams,
                agentId,
                "rider",
                replayStore.streamKeyRider(agentId),
                lastEventId,
                null);
    }

    @Override
    public SseEmitter subscribeCustomer(Long orderId, String lastEventId, Object snapshot) {
        return subscribe(
                customerStreams,
                orderId,
                "customer-order",
                replayStore.streamKeyOrder(orderId),
                lastEventId,
                snapshot);
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

    private SseEmitter subscribe(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams,
                                  Long key,
                                  String channel,
                                  String replayStreamKey,
                                  String lastEventId,
                                  Object snapshot) {
        reserveGlobalBudget(channel, key);
        SseEmitter emitter = new SseEmitter(DEFAULT_TIMEOUT);
        CopyOnWriteArrayList<SseEmitter> emitters = streams.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());

        synchronized (emitters) {
            if (emitters.size() >= liveProperties.getMaxEmittersPerStream()) {
                totalEmitters.decrementAndGet();
                log.warn("SSE_CAPACITY_EXCEEDED | channel={} | id={}", channel, key);
                throw new SseCapacityExceededException("Stream capacity reached for " + channel + " " + key);
            }
            emitters.add(emitter);
        }

        Runnable cleanup = () -> remove(streams, key, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        try {
            emitter.send(SseEmitter.event().id("0").name("connected").data("{\"channel\":\"" + channel + "\",\"id\":" + key + "}"));
            replayMissedEvents(emitter, replayStreamKey, lastEventId);
            if (snapshot != null) {
                emitter.send(SseEmitter.event().id("snapshot").name("order-snapshot").data(snapshot));
            }
        } catch (IOException e) {
            cleanup.run();
            throw new IllegalStateException("Failed to open SSE stream", e);
        }

        log.debug("SSE subscribed | channel={} | id={}", channel, key);
        return emitter;
    }

    private void replayMissedEvents(SseEmitter emitter, String replayStreamKey, String lastEventId) throws IOException {
        if (!StringUtils.hasText(lastEventId) || !StringUtils.hasText(replayStreamKey)) {
            return;
        }
        long afterEventId = replayStore.parseLastEventId(lastEventId);
        if (afterEventId < 0) {
            return;
        }
        List<OrderLiveUpdate> missed = replayStore.replayAfter(replayStreamKey, afterEventId);
        for (OrderLiveUpdate update : missed) {
            sendUpdate(emitter, update);
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
                    }
                });
            } catch (Exception ex) {
                try {
                    sendUpdate(emitter, update);
                } catch (Exception e) {
                    removeFromAllStreams(emitter);
                    removeAndCompleteEmitter(emitter);
                }
            }
        }
    }

    private void sendUpdate(SseEmitter emitter, OrderLiveUpdate update) throws IOException {
        SseEmitter.SseEventBuilder event = SseEmitter.event().name("order-update").data(update);
        if (update.getEventId() != null) {
            event.id(String.valueOf(update.getEventId()));
        }
        emitter.send(event);
    }

    private void removeFromAllStreams(SseEmitter emitter) {
        remove(kitchenStreams, emitter);
        remove(riderStreams, emitter);
        remove(customerStreams, emitter);
    }

    private void remove(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams, SseEmitter emitter) {
        for (Map.Entry<Long, CopyOnWriteArrayList<SseEmitter>> entry : streams.entrySet()) {
            remove(streams, entry.getKey(), emitter);
        }
    }

    private void reserveGlobalBudget(String channel, Long key) {
        int reserved = totalEmitters.incrementAndGet();
        if (reserved > liveProperties.getMaxTotalEmitters()) {
            totalEmitters.decrementAndGet();
            log.warn("SSE_GLOBAL_BUDGET_EXCEEDED | channel={} | id={} | total={}", channel, key, reserved);
            throw new SseCapacityExceededException("Server SSE connection budget reached; retry shortly");
        }
    }

    private void remove(Map<Long, CopyOnWriteArrayList<SseEmitter>> streams, Long key, SseEmitter emitter) {
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
                }
            }
            emitters.clear();
        }
        streams.clear();
    }
}
