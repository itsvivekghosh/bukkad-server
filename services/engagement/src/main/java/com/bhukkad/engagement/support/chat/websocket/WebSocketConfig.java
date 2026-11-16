package com.bhukkad.engagement.support.chat.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocket configuration for real-time chat messaging.
 *
 * <p>Configures STOMP over WebSocket with the following endpoints:</p>
 * <ul>
 *   <li>/ws - WebSocket endpoint for chat connections</li>
 *   <li>/topic/chat/{roomId} - Subscribe to room messages</li>
 *   <li>/app/chat.sendMessage - Send message to room</li>
 *   <li>/app/chat.joinRoom - Join a room</li>
 *   <li>/app/chat.leaveRoom - Leave a room</li>
 * </ul>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns("*")
            .withSockJS(); // Fallback for browsers without WebSocket support
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Enable simple message broker for topic-based messaging
        registry.enableSimpleBroker("/topic", "/queue");

        // Set application destination prefixes
        registry.setApplicationDestinationPrefixes("/app");

        // Set user destination prefix for user-specific messages
        registry.setUserDestinationPrefix("/user");
    }
}
