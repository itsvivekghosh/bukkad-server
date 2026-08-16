package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.cluster.live-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderLiveRedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final OrderLiveLocalDispatcher localDispatcher;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            return;
        }
        try {
            OrderLiveUpdate update = objectMapper.readValue(message.getBody(), OrderLiveUpdate.class);
            localDispatcher.dispatch(update);
        } catch (Exception e) {
            log.warn("Failed to process order live update from Redis relay: {}", e.getMessage());
        }
    }
}
