package com.bhukkad.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Random;
import java.util.Set;

/**
 * Request hedging filter for idempotent GET endpoints (resilience improvement).
 *
 * <p>Duplicates a configurable percentage of GET requests to a secondary
 * backend and races both responses, committing whichever arrives first. If
 * the hedged request errors or times out the original upstream response is
 * used instead (graceful degradation).</p>
 *
 * <p>The secondary URI must be explicitly configured via
 * {@code app.edge.hedging.secondary-uri}; when absent the filter is a no-op.
 * Only GET requests to public cacheable paths are eligible (the same set as
 * {@link com.bhukkad.gateway.cache.EdgeCacheFilter}).</p>
 */
@Component
public class EdgeRequestHedgingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(EdgeRequestHedgingFilter.class);
    static final int ORDER = 0;
    private static final Duration HEDGE_TIMEOUT = Duration.ofSeconds(8);
    private static final String METRIC_HEDGE_SENT = "edge_hedge_requests_total";
    private static final String METRIC_HEDGE_WON = "edge_hedge_won_total";

    private final WebClient.Builder webClientBuilder;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final String secondaryUri;
    private final double hedgeProbability;
    private final Random random = new Random();

    public EdgeRequestHedgingFilter(WebClient.Builder webClientBuilder,
                                    ObjectProvider<MeterRegistry> meterRegistryProvider,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${app.edge.hedging.secondary-uri:}") String secondaryUri,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${app.edge.hedging.probability:0.02}") double hedgeProbability) {
        this.webClientBuilder = webClientBuilder;
        this.meterRegistryProvider = meterRegistryProvider;
        this.secondaryUri = secondaryUri != null ? secondaryUri.trim() : "";
        this.hedgeProbability = secondaryUri.isEmpty() ? 0.0 : hedgeProbability;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (hedgeProbability <= 0.0 || secondaryUri.isEmpty()) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest();
        if (!HttpMethod.GET.equals(request.getMethod())) {
            return chain.filter(exchange);
        }
        if (!isEligiblePath(request)) {
            return chain.filter(exchange);
        }
        if (random.nextDouble() >= hedgeProbability) {
            return chain.filter(exchange);
        }

        countMetric(METRIC_HEDGE_SENT);
        URI originalUri = request.getURI();
        URI secondary = buildSecondaryUri(originalUri);
        if (secondary == null) {
            return chain.filter(exchange);
        }

        WebClient webClient = webClientBuilder.build();
        Mono<byte[]> hedged = webClient.method(HttpMethod.GET)
                .uri(secondary)
                .headers(headers -> headers.addAll(request.getHeaders()))
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(HEDGE_TIMEOUT)
                .doOnError(e -> log.debug("EDGE_HEDGE_FAILED secondary={} error={}", secondary, e.toString()))
                .onErrorResume(e -> Mono.empty());

        return Mono.firstWithSignal(
                hedged.flatMap(body -> {
                    ServerHttpResponse response = exchange.getResponse();
                    if (response.isCommitted()) {
                        return Mono.empty();
                    }
                    response.setStatusCode(HttpStatus.OK);
                    response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                    countMetric(METRIC_HEDGE_WON);
                    log.debug("EDGE_HEDGE_WON secondary={} path={}", secondary, originalUri.getPath());
                    return response.writeWith(
                            Mono.just(response.bufferFactory().wrap(body)));
                }),
                chain.filter(exchange)
        ).then();
    }

    private static final Set<String> ELIGIBLE_PREFIXES = Set.of(
            "/api/v1/restaurants/public",
            "/api/v1/cuisines",
            "/api/v1/menu/items",
            "/api/v1/reviews",
            "/api/v1/feed",
            "/api/v1/search",
            "/api/v1/home/feed",
            "/api/v1/home/banners",
            "/api/v1/home/trending"
    );

    private boolean isEligiblePath(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        return ELIGIBLE_PREFIXES.stream()
                .anyMatch(path::startsWith);
    }

    private URI buildSecondaryUri(URI originalUri) {
        try {
            String sec = secondaryUri;
            if (sec.endsWith("/")) {
                sec = sec.substring(0, sec.length() - 1);
            }
            String query = originalUri.getRawQuery();
            return URI.create(sec + originalUri.getRawPath()
                    + (query != null ? "?" + query : ""));
        } catch (Exception e) {
            log.warn("EDGE_HEDGE_BAD_URI secondary={} error={}", secondaryUri, e.getMessage());
            return null;
        }
    }

    private void countMetric(String name) {
        MeterRegistry meters = meterRegistryProvider.getIfAvailable();
        if (meters != null) {
            meters.counter(name).increment();
        }
    }
}
