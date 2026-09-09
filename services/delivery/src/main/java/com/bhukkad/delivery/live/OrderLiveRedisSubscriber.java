package com.bhukkad.delivery.live;

import com.bhukkad.delivery.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.cluster.live-relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OrderLiveRedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final OrderLiveLocalDispatcher localDispatcher;
    private final Executor sseDispatchExecutor;

    public OrderLiveRedisSubscriber(ObjectMapper objectMapper,
                                    OrderLiveLocalDispatcher localDispatcher,
                                    @Qualifier("sseDispatchExecutor") Executor sseDispatchExecutor) {
        this.objectMapper = objectMapper;
        this.localDispatcher = localDispatcher;
        this.sseDispatchExecutor = sseDispatchExecutor;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null || message.getBody().length == 0) {
            return;
        }
        try {
            OrderLiveUpdate update = objectMapper.readValue(message.getBody(), OrderLiveUpdate.class);
            sseDispatchExecutor.execute(() -> localDispatcher.dispatch(update));
        } catch (Exception e) {
            log.warn("Failed to process order live update from Redis relay: {}", e.getMessage());
        }
    }
}
