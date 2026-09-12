package com.bhukkad.realtime.domain.service.impl;

import com.bhukkad.realtime.config.LiveProperties;
import com.bhukkad.realtime.domain.event.OrderLiveUpdate;
import com.bhukkad.realtime.domain.service.OrderLiveReplayStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisOrderLiveReplayStore implements OrderLiveReplayStore {

    private static final String EVENT_ID_SEQUENCE_KEY = "live:event-id-seq";
    private static final String REPLAY_KEY_PREFIX = "live:replay:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final LiveProperties liveProperties;

    @Override
    public long nextEventId() {
        Long eventId = stringRedisTemplate.opsForValue().increment(EVENT_ID_SEQUENCE_KEY);
        return eventId != null ? eventId : System.nanoTime();
    }

    @Override
    public void record(OrderLiveUpdate update) {
        if (update == null || update.getEventId() == null) {
            return;
        }
        if (update.getRestaurantId() != null) {
            append(streamKeyKitchen(update.getRestaurantId()), update);
        }
        if (update.getOrderId() != null) {
            append(streamKeyOrder(update.getOrderId()), update);
        }
        if (update.getDeliveryAgentId() != null) {
            append(streamKeyRider(update.getDeliveryAgentId()), update);
        }
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

    @Override
    public String streamKeyKitchen(Long restaurantId) {
        return restaurantId != null ? "kitchen:" + restaurantId : null;
    }

    @Override
    public String streamKeyOrder(Long orderId) {
        return orderId != null ? "order:" + orderId : null;
    }

    @Override
    public String streamKeyRider(Long agentId) {
        return agentId != null ? "rider:" + agentId : null;
    }

    @Override
    public long parseLastEventId(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private void append(String streamKey, OrderLiveUpdate update) {
        if (streamKey == null) {
            return;
        }
        try {
            String redisKey = REPLAY_KEY_PREFIX + streamKey;
            String payload = objectMapper.writeValueAsString(update);
            stringRedisTemplate.opsForZSet().add(redisKey, payload, update.getEventId());
            trim(redisKey);
            stringRedisTemplate.expire(redisKey, Duration.ofSeconds(liveProperties.getReplay().getTtlSeconds()));
        } catch (JsonProcessingException ex) {
            log.warn("LIVE_REPLAY_APPEND_FAILED | stream={} | error={}", streamKey, ex.getMessage());
        }
    }

    private void trim(String redisKey) {
        Long size = stringRedisTemplate.opsForZSet().size(redisKey);
        if (size == null || size <= liveProperties.getReplay().getMaxEventsPerStream()) {
            return;
        }
        long excess = size - liveProperties.getReplay().getMaxEventsPerStream();
        stringRedisTemplate.opsForZSet().removeRange(redisKey, 0, excess - 1);
    }
}
