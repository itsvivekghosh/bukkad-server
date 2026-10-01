package com.bhukkad.catalog.personalization.infrastructure.client;

import com.bhukkad.common.security.ServiceJwtAuthTokenProvider;
import com.bhukkad.common.web.client.PlatformWebClientBuilderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

/**
 * Reads a customer's delivered-order history from commerce for the
 * personalization engine.
 *
 * <p>Order data is owned by commerce. Catalog used to run the same aggregate
 * SQL against its own datasource, where {@code orders}/{@code order_items} do
 * not exist, so every recommendation call failed with
 * {@code BadSqlGrammarException} and returned 500.</p>
 *
 * <p>Uses the platform WebClient factory (bounded pool, 2s connect / 5s
 * response timeouts, circuit breaker on target {@code commerce}) and stamps the
 * mesh service token on {@code X-Service-Token}, which commerce's
 * {@code /api/v1/internal/**} guard requires.</p>
 */
@Slf4j
@Component
public class OrderHistoryClient {

    private static final String TARGET = "commerce";

    /** Upper bound on a blocking wait; recommendations must fail fast. */
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(6);

    private final WebClient webClient;
    private final ObjectProvider<ServiceJwtAuthTokenProvider> authTokenProvider;

    public OrderHistoryClient(
            @Value("${app.service.commerce.url}") String baseUrl,
            ObjectProvider<ServiceJwtAuthTokenProvider> authTokenProvider,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.authTokenProvider = authTokenProvider;
        this.webClient = PlatformWebClientBuilderFactory.forTarget(TARGET,
                        meterRegistryProvider.getIfAvailable())
                .build()
                .mutate()
                .baseUrl(baseUrl)
                .filter((request, next) -> {
                    String token = meshToken();
                    return next.exchange(token == null ? request
                            : ClientRequest.from(request).header("X-Service-Token", token).build());
                })
                .build();
    }

    private String meshToken() {
        ServiceJwtAuthTokenProvider provider = authTokenProvider.getIfAvailable();
        return provider == null ? null : provider.serviceToken();
    }

    public record ItemFrequency(Long menuItemId, long frequency) {
    }

    public record RestaurantAffinity(Long restaurantId, long visits) {
    }

    public record CoOrderedResponse(List<ItemFrequency> items) {
    }

    public record CoOrderedRequest(List<Long> itemIds) {
    }

    public List<ItemFrequency> itemFrequencies(Long customerId, int limit) {
        return get("/api/v1/internal/recommendations/customers/{id}/item-frequencies?limit={limit}",
                new ParameterizedTypeReference<List<ItemFrequency>>() {
                }, customerId, limit);
    }

    public List<RestaurantAffinity> restaurantAffinities(Long customerId, int limit) {
        return get("/api/v1/internal/recommendations/customers/{id}/restaurant-affinities?limit={limit}",
                new ParameterizedTypeReference<List<RestaurantAffinity>>() {
                }, customerId, limit);
    }

    public List<ItemFrequency> coOrdered(List<Long> itemIds, Long customerId, int limit) {
        if (itemIds == null || itemIds.isEmpty()) {
            return List.of();
        }
        try {
            CoOrderedResponse res = webClient.post()
                    .uri("/api/v1/internal/recommendations/customers/{id}/co-ordered?limit={limit}",
                            customerId, limit)
                    .header("Content-Type", "application/json")
                    .headers(headers -> {
                        String token = meshToken();
                        if (token != null) {
                            headers.set("X-Service-Token", token);
                        }
                    })
                    .bodyValue(new CoOrderedRequest(itemIds))
                    .retrieve()
                    .bodyToMono(CoOrderedResponse.class)
                    .block(BLOCK_TIMEOUT);
            return res == null || res.items() == null ? List.of() : res.items();
        } catch (RuntimeException ex) {
            // Recommendations degrade to "no signal" rather than failing the
            // customer's request; the circuit breaker records the outage.
            log.warn("ORDER_HISTORY_UNAVAILABLE op=co-ordered customerId={} error={}",
                    customerId, ex.toString());
            return List.of();
        }
    }

    private <T> T get(String uriTemplate, ParameterizedTypeReference<T> type, Object... vars) {
        try {
            T res = webClient.get()
                    .uri(uriTemplate, vars)
                    .headers(headers -> {
                        String token = meshToken();
                        if (token != null) {
                            headers.set("X-Service-Token", token);
                        }
                    })
                    .retrieve()
                    .bodyToMono(type)
                    .block(BLOCK_TIMEOUT);
            return res;
        } catch (RuntimeException ex) {
            log.warn("ORDER_HISTORY_UNAVAILABLE uri={} error={}", uriTemplate, ex.toString());
            return null;
        }
    }
}
