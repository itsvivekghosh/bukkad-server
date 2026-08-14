package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.live.OrderLiveAccessService;
import com.bhukkad.live.OrderLiveTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    private final OrderLiveAccessService orderLiveAccessService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            User user = getSessionUser(accessor);
            if (user == null) {
                log.warn("STOMP connect rejected: missing authenticated user");
                return null;
            }
            accessor.setUser(new StompPrincipal(user));
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            User user = resolveUser(accessor);
            if (user == null) {
                log.warn("STOMP subscribe rejected: unauthenticated");
                return null;
            }

            String destination = accessor.getDestination();
            if (!isAllowedSubscription(user, destination)) {
                log.warn("STOMP subscribe rejected | userId={} | destination={}", user.getId(), destination);
                return null;
            }
        }

        return message;
    }

    private boolean isAllowedSubscription(User user, @Nullable String destination) {
        if (destination == null) {
            return false;
        }

        if (destination.startsWith(OrderLiveTopics.KITCHEN_PREFIX)) {
            Long restaurantId = parseId(destination, OrderLiveTopics.KITCHEN_PREFIX);
            return restaurantId != null && orderLiveAccessService.canSubscribeKitchen(user, restaurantId);
        }

        if (destination.startsWith(OrderLiveTopics.RIDER_PREFIX)) {
            Long agentId = parseId(destination, OrderLiveTopics.RIDER_PREFIX);
            return agentId != null && orderLiveAccessService.canSubscribeRider(user, agentId);
        }

        if (destination.startsWith(OrderLiveTopics.CUSTOMER_PREFIX)) {
            Long orderId = parseId(destination, OrderLiveTopics.CUSTOMER_PREFIX);
            return orderId != null && orderLiveAccessService.canSubscribeCustomer(user, orderId);
        }

        return false;
    }

    @Nullable
    private Long parseId(String destination, String prefix) {
        try {
            return Long.parseLong(destination.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private User resolveUser(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal instanceof StompPrincipal stompPrincipal) {
            return stompPrincipal.user();
        }
        return getSessionUser(accessor);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private User getSessionUser(StompHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            return null;
        }
        Object user = sessionAttributes.get("user");
        return user instanceof User authenticatedUser ? authenticatedUser : null;
    }
}
