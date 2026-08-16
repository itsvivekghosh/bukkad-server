package com.bhukkad.config;

import com.bhukkad.security.JwtHandshakeInterceptor;
import com.bhukkad.security.StompAuthChannelInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableScheduling
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final StompAuthChannelInterceptor stompAuthChannelInterceptor;
    private final StompBrokerProperties stompBrokerProperties;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        if (stompBrokerProperties.getType() == StompBrokerProperties.BrokerType.RABBITMQ) {
            StompBrokerProperties.Rabbit rabbit = stompBrokerProperties.getRabbit();
            config.enableStompBrokerRelay("/topic")
                    .setRelayHost(rabbit.getHost())
                    .setRelayPort(rabbit.getPort())
                    .setClientLogin(rabbit.getUsername())
                    .setClientPasscode(rabbit.getPassword())
                    .setSystemLogin(rabbit.getUsername())
                    .setSystemPasscode(rabbit.getPassword())
                    .setVirtualHost(rabbit.getVirtualHost());
        } else {
            config.enableSimpleBroker("/topic");
        }
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(jwtHandshakeInterceptor)
                .withSockJS();

        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns("*")
                .addInterceptors(jwtHandshakeInterceptor);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
