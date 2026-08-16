package com.bhukkad.live;

import com.bhukkad.cluster.ClusterProperties;
import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.cluster.live-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisOrderLiveRelay implements OrderLiveRelay {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final ClusterProperties clusterProperties;

    @Override
    public void publish(OrderLiveUpdate update) {
        try {
            String payload = objectMapper.writeValueAsString(update);
            stringRedisTemplate.convertAndSend(clusterProperties.getLiveRelay().getChannel(), payload);
            log.debug("LIVE_UPDATE_RELAY | channel={} | orderId={}",
                    clusterProperties.getLiveRelay().getChannel(), update.getOrderId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize order live update for Redis relay", e);
        }
    }
}
