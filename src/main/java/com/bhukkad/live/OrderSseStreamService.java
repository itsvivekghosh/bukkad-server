package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSseStreamService {

    private final OrderLiveReplayStore replayStore;

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
        SseEmitter emitter = new SseEmitter(0L);
        streams.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

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
                sendUpdate(emitter, update);
            } catch (Exception e) {
                emitters.remove(emitter);
                log.debug("SSE emitter removed after send failure: {}", e.getMessage());
            }
        }
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
                        Long key,
                        SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = streams.get(key);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                streams.remove(key, emitters);
            }
        }
    }

    public void sendHeartbeats() {
        sendHeartbeatToAll(kitchenStreams);
        sendHeartbeatToAll(riderStreams);
        sendHeartbeatToAll(customerStreams);
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
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                emitters.remove(emitter);
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
}
