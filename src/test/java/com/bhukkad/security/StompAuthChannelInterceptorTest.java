package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.live.OrderLiveAccessService;
import com.bhukkad.live.OrderLiveTopics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StompAuthChannelInterceptorTest {

    @Mock
    private OrderLiveAccessService orderLiveAccessService;

    @Mock
    private MessageChannel channel;

    @InjectMocks
    private StompAuthChannelInterceptor interceptor;

    @Test
    void preSend_returnsMessageWhenAccessorMissing() {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0]).build();

        Message<?> result = interceptor.preSend(message, channel);

        assertSame(message, result);
    }

    @Test
    void preSend_connectRejectsUnauthenticatedUser() {
        Message<byte[]> message = stompMessage(StompCommand.CONNECT, null, null);

        assertNull(interceptor.preSend(message, channel));
    }

    @Test
    void preSend_connectAcceptsAuthenticatedUser() {
        User user = user(1L);
        Message<byte[]> message = stompMessage(StompCommand.CONNECT, null, user);

        Message<?> result = interceptor.preSend(message, channel);

        assertNotNull(result);
    }

    @Test
    void preSend_subscribeRejectsUnauthenticatedUser() {
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/kitchen/1", null);

        assertNull(interceptor.preSend(message, channel));
    }

    @Test
    void preSend_subscribeAllowsKitchenTopicForOwner() {
        User user = user(1L);
        when(orderLiveAccessService.canSubscribeKitchen(user, 10L)).thenReturn(true);
        Message<byte[]> message = stompMessage(
                StompCommand.SUBSCRIBE, OrderLiveTopics.kitchen(10L), user);

        Message<?> result = interceptor.preSend(message, channel);

        assertNotNull(result);
    }

    @Test
    void preSend_subscribeRejectsInvalidKitchenDestination() {
        User user = user(1L);
        Message<byte[]> message = stompMessage(
                StompCommand.SUBSCRIBE, OrderLiveTopics.KITCHEN_PREFIX + "bad", user);

        assertNull(interceptor.preSend(message, channel));
    }

    @Test
    void preSend_subscribeAllowsRiderTopicForAgent() {
        User user = user(7L);
        user.setRole(User.UserRole.DELIVERY_AGENT);
        when(orderLiveAccessService.canSubscribeRider(user, 7L)).thenReturn(true);
        Message<byte[]> message = stompMessage(
                StompCommand.SUBSCRIBE, OrderLiveTopics.rider(7L), user);

        Message<?> result = interceptor.preSend(message, channel);

        assertNotNull(result);
    }

    @Test
    void preSend_subscribeRejectsUnknownDestination() {
        User user = user(1L);
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/unknown/1", user);

        assertNull(interceptor.preSend(message, channel));
    }

    @Test
    void preSend_subscribeUsesStompPrincipal() {
        User user = user(1L);
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination(OrderLiveTopics.kitchen(10L));
        accessor.setUser(new StompPrincipal(user));
        when(orderLiveAccessService.canSubscribeKitchen(user, 10L)).thenReturn(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertNotNull(result);
    }

    private static Message<byte[]> stompMessage(StompCommand command, String destination, User user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (user != null) {
            Map<String, Object> sessionAttributes = new HashMap<>();
            sessionAttributes.put("user", user);
            accessor.setSessionAttributes(sessionAttributes);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@test.com");
        user.setRole(User.UserRole.RESTAURANT_OWNER);
        return user;
    }
}
