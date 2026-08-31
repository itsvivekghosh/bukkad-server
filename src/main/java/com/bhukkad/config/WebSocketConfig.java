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

    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:4200}")
    private String allowedOrigins;

    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origin-patterns:}")
    private String allowedOriginPatterns;

    private String[] resolvedAllowedOrigins() {
        if (allowedOriginPatterns != null && !allowedOriginPatterns.isBlank()) {
            return allowedOriginPatterns.split(",");
        }
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            return allowedOrigins.split(",");
        }
        return new String[]{"http://localhost:3000"};
    }

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
        String[] origins = resolvedAllowedOrigins();
        // Use patterns to allow subdomains when needed (e.g. https://*.bhukkad.com)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .addInterceptors(jwtHandshakeInterceptor)
                .withSockJS();

        registry.addEndpoint("/ws-native")
                .setAllowedOriginPatterns(origins)
                .addInterceptors(jwtHandshakeInterceptor);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompAuthChannelInterceptor);
    }
}
