package com.bhukkad.config;

import com.bhukkad.security.JwtHandshakeInterceptor;
import com.bhukkad.security.StompAuthChannelInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

    @Mock
    private JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Mock
    private StompAuthChannelInterceptor stompAuthChannelInterceptor;

    @Test
    void configureMessageBroker_enablesTopicBroker() {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class, RETURNS_DEEP_STUBS);

        config.configureMessageBroker(registry);

        verify(registry).enableSimpleBroker("/topic");
        verify(registry).setApplicationDestinationPrefixes("/app");
    }

    @Test
    void configureMessageBroker_enablesRabbitRelayWhenConfigured() {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        brokerProperties.setType(StompBrokerProperties.BrokerType.RABBITMQ);
        brokerProperties.getRabbit().setHost("rabbit");
        brokerProperties.getRabbit().setPort(61613);
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        MessageBrokerRegistry registry = mock(MessageBrokerRegistry.class, RETURNS_DEEP_STUBS);

        config.configureMessageBroker(registry);

        verify(registry).enableStompBrokerRelay("/topic");
        verify(registry, never()).enableSimpleBroker("/topic");
    }

    // ===== Batch D: CORS origin resolution branches =====

    @Test
    void registerStompEndpoints_prefersAllowedOriginPatternsWhenSet() throws Exception {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOrigins", "https://a.com");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOriginPatterns", "https://*.bhukkad.com,https://b.com");

        java.lang.reflect.Method m = WebSocketConfig.class.getDeclaredMethod("resolvedAllowedOrigins");
        m.setAccessible(true);
        String[] origins = (String[]) m.invoke(config);

        // Patterns take precedence over plain origins
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{"https://*.bhukkad.com", "https://b.com"}, origins);
    }

    @Test
    void registerStompEndpoints_fallsBackToAllowedOriginsWhenPatternsBlank() throws Exception {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOrigins", "https://a.com, https://b.com");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOriginPatterns", "  ");

        java.lang.reflect.Method m = WebSocketConfig.class.getDeclaredMethod("resolvedAllowedOrigins");
        m.setAccessible(true);
        String[] origins = (String[]) m.invoke(config);

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{"https://a.com", " https://b.com"}, origins);
    }

    @Test
    void registerStompEndpoints_defaultsToLocalhost3000WhenNothingSet() throws Exception {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOrigins", "");
        org.springframework.test.util.ReflectionTestUtils.setField(config, "allowedOriginPatterns", null);

        java.lang.reflect.Method m = WebSocketConfig.class.getDeclaredMethod("resolvedAllowedOrigins");
        m.setAccessible(true);
        String[] origins = (String[]) m.invoke(config);

        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[]{"http://localhost:3000"}, origins);
    }

    @Test
    void registerStompEndpoints_registersSockJsAndNativeEndpoints() {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        StompEndpointRegistry registry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration sockJsEndpoint = mock(StompWebSocketEndpointRegistration.class);
        StompWebSocketEndpointRegistration nativeEndpoint = mock(StompWebSocketEndpointRegistration.class);

        when(registry.addEndpoint("/ws")).thenReturn(sockJsEndpoint);
        when(sockJsEndpoint.setAllowedOriginPatterns(new String[]{"http://localhost:3000"})).thenReturn(sockJsEndpoint);
        when(sockJsEndpoint.addInterceptors(jwtHandshakeInterceptor)).thenReturn(sockJsEndpoint);
        when(sockJsEndpoint.withSockJS()).thenReturn(mock(org.springframework.web.socket.config.annotation.SockJsServiceRegistration.class));
        when(registry.addEndpoint("/ws-native")).thenReturn(nativeEndpoint);
        when(nativeEndpoint.setAllowedOriginPatterns(new String[]{"http://localhost:3000"})).thenReturn(nativeEndpoint);
        when(nativeEndpoint.addInterceptors(jwtHandshakeInterceptor)).thenReturn(nativeEndpoint);

        assertDoesNotThrow(() -> config.registerStompEndpoints(registry));

        verify(sockJsEndpoint).withSockJS();
        verify(nativeEndpoint).addInterceptors(jwtHandshakeInterceptor);
    }

    @Test
    void configureClientInboundChannel_registersAuthInterceptor() {
        StompBrokerProperties brokerProperties = new StompBrokerProperties();
        WebSocketConfig config = new WebSocketConfig(
                jwtHandshakeInterceptor, stompAuthChannelInterceptor, brokerProperties);
        ChannelRegistration registration = mock(ChannelRegistration.class);

        config.configureClientInboundChannel(registration);

        verify(registration).interceptors(stompAuthChannelInterceptor);
    }
}
