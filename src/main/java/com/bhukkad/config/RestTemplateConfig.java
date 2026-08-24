package com.bhukkad.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Provides the application-wide {@link RestTemplate} used for outbound HTTP
 * calls (Twilio SMS/WhatsApp, FCM push, payment gateway webhooks, etc.).
 *
 * <p>Timeouts are mandatory for any outbound call: without them a hung remote
 * service would block the calling thread indefinitely. Defaults are 5s connect
 * and 10s read; override via {@code app.http.timeout.*} in each environment.</p>
 */
@Configuration
public class RestTemplateConfig {

    @Value("${app.http.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${app.http.read-timeout-ms:10000}")
    private long readTimeoutMs;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
