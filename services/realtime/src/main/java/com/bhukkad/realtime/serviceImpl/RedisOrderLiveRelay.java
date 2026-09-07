package com.bhukkad.realtime.serviceImpl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.dto.LiveUpdateEvent;
import com.bhukkad.realtime.dto.OrderLiveUpdate;
import com.bhukkad.realtime.service.OrderLiveRelay;
import com.bhukkad.realtime.service.OrderLiveReplayStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisOrderLiveRelay implements OrderLiveRelay {

    private static final String EVENT_ID_SEQUENCE_KEY = "live:event-id-seq";
    private static final String CHANNEL_PREFIX = "live:channel:";
    private static final String REPLAY_KEY_PREFIX = "live:replay:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProperties liveProperties;
    private final RedisMessageListenerContainer listenerContainer;
    private final OrderLiveReplayStore replayStore;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<OrderLiveUpdate>>> localConsumers = new ConcurrentHashMap<>();

    @Override
    public void relay(LiveUpdateEvent event) {
        if (event == null || event.getPayload() == null) {
            log.warn("LIVE_RELAY_REJECTED | type={} | reason=null-payload",
                    event == null ? null : event.getType());
            return;
        }
        OrderLiveUpdate update = toOrderLiveUpdate(event.getPayload());
        if (update == null) {
            log.warn("LIVE_RELAY_REJECTED | type={} | reason=unmapped-payload", event.getType());
            return;
        }
        publish(update);  // publish() owns id assignment + replay recording
        log.debug("LIVE_RELAY_DISPATCHED | type={} | eventId={} | orderId={}",
                event.getType(), update.getEventId(), update.getOrderId());
    }

    private OrderLiveUpdate toOrderLiveUpdate(Object payload) {
        if (payload instanceof OrderLiveUpdate update) {
            return update;
        }
        try {
            return objectMapper.convertValue(payload, OrderLiveUpdate.class);
        } catch (Exception ex) {
            log.warn("LIVE_RELAY_PAYLOAD_CONVERSION_FAILED | error={}", ex.getMessage());
            return null;
        }
    }

    @Override
    public void publish(OrderLiveUpdate update) {
        if (update == null) {
            return;
        }
        try {
            if (update.getEventId() == null) {
                update.setEventId(replayStore.nextEventId());
                replayStore.record(update);
            }
            String payload = objectMapper.writeValueAsString(update);
            // Fan out to EVERY stream this update belongs to: a customer order
            // update is also visible to the kitchen and rider streams (the old
            // first-match channel selection made cross-channel delivery
            // impossible even with subscribers present).
            for (String channel : channelsFor(update)) {
                stringRedisTemplate.convertAndSend(channel, payload);
            }
            log.debug("LIVE_UPDATE_PUBLISHED | eventId={} | type={} | orderId={}",
                    update.getEventId(), update.getEventType(), update.getOrderId());
        } catch (Exception ex) {
            log.error("Failed to publish live update: {}", ex.getMessage());
        }
    }

    @Override
    public void subscribe(String topic, Consumer<OrderLiveUpdate> consumer) {
        CopyOnWriteArrayList<Consumer<OrderLiveUpdate>> consumers = localConsumers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>());
        consumers.add(consumer);

        String channel = CHANNEL_PREFIX + topic;
        listenerContainer.addMessageListener((MessageListener) (message, pattern) -> {
            try {
                OrderLiveUpdate update = objectMapper.readValue(new String(message.getBody()), OrderLiveUpdate.class);
                for (Consumer<OrderLiveUpdate> c : localConsumers.getOrDefault(topic, new CopyOnWriteArrayList<>())) {
                    try {
                        c.accept(update);
                    } catch (Exception ex) {
                        log.warn("Consumer error for topic {}: {}", topic, ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to deserialize live update: {}", ex.getMessage());
            }
        }, new ChannelTopic(channel));
    }

    @Override
    public List<OrderLiveUpdate> replayAfter(String streamKey, long lastEventId) {
        if (streamKey == null || lastEventId < 0) {
            return List.of();
        }
        try {
            String redisKey = REPLAY_KEY_PREFIX + streamKey;
            Set<String> payloads = stringRedisTemplate.opsForZSet()
                    .rangeByScore(redisKey, lastEventId + 1D, Double.MAX_VALUE);
            if (payloads == null || payloads.isEmpty()) {
                return List.of();
            }
            List<OrderLiveUpdate> updates = new ArrayList<>(payloads.size());
            for (String payload : payloads) {
                updates.add(objectMapper.readValue(payload, OrderLiveUpdate.class));
            }
            return updates;
        } catch (Exception ex) {
            log.warn("LIVE_REPLAY_FAILED | stream={} | error={}", streamKey, ex.getMessage());
            return List.of();
        }
    }

    private java.util.List<String> channelsFor(OrderLiveUpdate update) {
        java.util.List<String> channels = new java.util.ArrayList<>(3);
        if (update.getOrderId() != null) {
            channels.add(CHANNEL_PREFIX + "order:" + update.getOrderId());
        }
        if (update.getRestaurantId() != null) {
            channels.add(CHANNEL_PREFIX + "kitchen:" + update.getRestaurantId());
        }
        if (update.getDeliveryAgentId() != null) {
            channels.add(CHANNEL_PREFIX + "rider:" + update.getDeliveryAgentId());
        }
        return channels;
    }

    @Override
    public void subscribeAll(java.util.function.BiConsumer<String, OrderLiveUpdate> sink) {
        listenerContainer.addMessageListener((MessageListener) (message, pattern) -> {
            try {
                OrderLiveUpdate update = objectMapper.readValue(
                        new String(message.getBody()), OrderLiveUpdate.class);
                if (update != null) {
                    sink.accept(new String(message.getChannel()), update);
                }
            } catch (Exception ex) {
                log.warn("LIVE_BRIDGE_DESERIALIZE_FAILED | error={}", ex.getMessage());
            }
        }, new PatternTopic(CHANNEL_PREFIX + "*"));
        log.info("LIVE_BRIDGE_SUBSCRIBED pattern={}*", CHANNEL_PREFIX);
    }
}
