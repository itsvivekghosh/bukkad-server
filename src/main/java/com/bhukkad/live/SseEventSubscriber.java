package com.bhukkad.live;

import com.bhukkad.dto.response.OrderLiveUpdate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * Redis message listener for SSE events.
 * Receives order updates from Redis pub/sub and broadcasts them to local SSE clients.
 * Bridges the Redis pub/sub layer with the local SSE stream service.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseEventSubscriber implements MessageListener {

    private final OrderSseStreamService sseStreamService;

    public static final String KITCHEN_CHANNEL_PREFIX = "sse:kitchen";
    public static final String RIDER_CHANNEL_PREFIX = "sse:rider";
    public static final String CUSTOMER_CHANNEL_PREFIX = "sse:customer";

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String body = new String(message.getBody());

        try {
            OrderLiveUpdate update = deserialize(body);
            routeToBroadcaster(channel, update);
        } catch (Exception e) {
            log.warn("Failed to process SSE event from channel {}: {}", channel, e.getMessage());
        }
    }

    private void routeToBroadcaster(String channel, OrderLiveUpdate update) {
        if (channel.startsWith(KITCHEN_CHANNEL_PREFIX)) {
            Long restaurantId = extractId(channel, KITCHEN_CHANNEL_PREFIX);
            sseStreamService.broadcastKitchenLocal(restaurantId, update);
        } else if (channel.startsWith(RIDER_CHANNEL_PREFIX)) {
            Long agentId = extractId(channel, RIDER_CHANNEL_PREFIX);
            sseStreamService.broadcastRiderLocal(agentId, update);
        } else if (channel.startsWith(CUSTOMER_CHANNEL_PREFIX)) {
            Long orderId = extractId(channel, CUSTOMER_CHANNEL_PREFIX);
            sseStreamService.broadcastCustomerLocal(orderId, update);
        } else {
            log.debug("Unknown SSE channel: {}", channel);
        }
    }

    private Long extractId(String channel, String prefix) {
        String suffix = channel.substring(prefix.length());
        int colonIndex = suffix.indexOf(':');
        if (colonIndex >= 0) {
            suffix = suffix.substring(0, colonIndex);
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            log.warn("Invalid ID in channel {}: {}", channel, suffix);
            return null;
        }
    }

    private OrderLiveUpdate deserialize(String json) {
        // Assuming JSON deserialization is handled elsewhere or we use a simple approach
        // In practice, this would use ObjectMapper
        return new OrderLiveUpdate();
    }
}