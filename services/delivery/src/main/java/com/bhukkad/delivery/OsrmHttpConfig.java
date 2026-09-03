package com.bhukkad.delivery;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Delivery-service-local HTTP client for OSRM route/distance calls.
 * Ported from monolith {@code com.bhukkad.config.RestTemplateConfig#osrmRestTemplate}
 * so {@link OsrmClient} resolves its {@code @Qualifier("osrmRestTemplate")} dependency
 * without the monolith config class.
 */
@Configuration
public class OsrmHttpConfig {

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
