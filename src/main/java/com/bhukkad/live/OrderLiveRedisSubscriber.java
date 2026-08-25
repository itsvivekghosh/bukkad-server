package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/**
 * Consumes order live updates from the Redis relay channel and dispatches them
 * to the local SSE/STOMP subscribers.
 *
 * <p>The {@code onMessage} callback runs on the Redis (Lettuce) pub/sub
 * listener thread. Downstream fan-out performs blocking socket writes to SSE
 * emitters, so a slow client would otherwise stall Redis message delivery for
 * every stream on this pod. The dispatch is therefore submitted to the bounded
 * {@code sseDispatchExecutor} pool: the listener thread returns immediately and
 * each fan-out runs on a dedicated worker. If the queue is full the update is
 * dropped for this cycle and the affected client recovers via the replay store
 * on reconnect (at-least-once with bounded loss, never blocking the broker).
 */
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
