package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderLiveReplayStore {

    static final String EVENT_ID_SEQUENCE_KEY = "live:event-id-seq";
    private static final String REPLAY_KEY_PREFIX = "live:replay:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final OrderLiveReplayProperties replayProperties;

    public long nextEventId() {
        Long eventId = stringRedisTemplate.opsForValue().increment(EVENT_ID_SEQUENCE_KEY);
        return eventId != null ? eventId : System.nanoTime();
    }

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

    public List<OrderLiveUpdate> replayAfter(String streamKey, long lastEventId) {
        if (!StringUtils.hasText(streamKey) || lastEventId < 0) {
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

    public static String streamKeyKitchen(Long restaurantId) {
        return restaurantId != null ? "kitchen:" + restaurantId : null;
    }

    public static String streamKeyOrder(Long orderId) {
        return orderId != null ? "order:" + orderId : null;
    }

    public static String streamKeyRider(Long agentId) {
        return agentId != null ? "rider:" + agentId : null;
    }

    public static long parseLastEventId(String lastEventId) {
        if (!StringUtils.hasText(lastEventId)) {
            return -1L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private void append(String streamKey, OrderLiveUpdate update) {
        if (!StringUtils.hasText(streamKey)) {
            return;
        }
        try {
            String redisKey = REPLAY_KEY_PREFIX + streamKey;
            String payload = objectMapper.writeValueAsString(update);
            stringRedisTemplate.opsForZSet().add(redisKey, payload, update.getEventId());
            trim(redisKey);
            stringRedisTemplate.expire(redisKey, Duration.ofSeconds(replayProperties.getTtlSeconds()));
        } catch (JsonProcessingException ex) {
            log.warn("LIVE_REPLAY_APPEND_FAILED | stream={} | error={}", streamKey, ex.getMessage());
        }
    }

    private void trim(String redisKey) {
        Long size = stringRedisTemplate.opsForZSet().size(redisKey);
        if (size == null || size <= replayProperties.getMaxEventsPerStream()) {
            return;
        }
        long excess = size - replayProperties.getMaxEventsPerStream();
        stringRedisTemplate.opsForZSet().removeRange(redisKey, 0, excess - 1);
    }
}
