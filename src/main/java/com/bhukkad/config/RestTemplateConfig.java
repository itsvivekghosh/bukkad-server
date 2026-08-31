package com.bhukkad.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
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
@SuppressWarnings("deprecation") // RequestConfig.Builder#setConnectTimeout is deprecated in httpclient5 5.2.1 (Spring Boot 3.2-managed) with no non-deprecated replacement
public class RestTemplateConfig {

    @Value("${app.http.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${app.http.read-timeout-ms:10000}")
    private long readTimeoutMs;

    @Value("${app.http.max-connections:400}")
    private int maxConnections;

    @Value("${app.http.max-connections-per-route:100}")
    private int maxPerRoute;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxPerRoute);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(2000))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);

        // Timeouts are already baked into the Apache HttpClient 5 RequestConfig above.
        // Do NOT call RestTemplateBuilder.setConnectTimeout/setReadTimeout here:
        // that path tries to invoke setReadTimeout/setConnectTimeout on the factory,
        // which only works for the legacy Apache HttpClient 4.x bridge.
        return builder
                .requestFactory(() -> factory)
                .build();
    }

    @Bean
    public RestClient razorpayRestClient(RestClient.Builder builder) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxPerRoute);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(Math.min(connectTimeoutMs, 3000)))
                .setResponseTimeout(Timeout.ofMilliseconds(Math.min(readTimeoutMs, 5000)))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(2000))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return builder
                .baseUrl("https://api.razorpay.com")
                .requestFactory(factory)
                .build();
    }

    @Bean
    public RestClient webhookRestClient(RestClient.Builder builder) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(2000))
                .setResponseTimeout(Timeout.ofMilliseconds(5000))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(1000))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return builder.requestFactory(factory).build();
    }

    @Bean(name = "osrmRestTemplate")
    public RestTemplate osrmRestTemplate(
            @Value("${app.road-distance.connect-timeout-ms:2000}") long connectTimeoutMs,
            @Value("${app.road-distance.read-timeout-ms:2000}") long readTimeoutMs) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(50);
        connectionManager.setDefaultMaxPerRoute(20);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(1000))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictIdleConnections(Timeout.ofSeconds(30))
                .build();

        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        return new RestTemplate(factory);
    }
}
