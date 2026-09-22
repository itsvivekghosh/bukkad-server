package com.bhukkad.gateway.sse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

/**
 * Consistent-hash filter for SSE streams (Phase 6).
 *
 * <p>Routes {@code /api/v1/live/{userId}} requests to a stable realtime pod
 * instance based on {@code userId} hash. This gives session affinity without
 * service discovery; the list of backends is configured in
 * {@code app.sse.realtime-instances}.
 *
 * <p>When the list is empty or the path does not match, requests pass through
 * unchanged.
 */
@Component
@ConfigurationProperties(prefix = "app.sse")
public class ConsistentHashSseFilter implements WebFilter {

    /**
     * Realtime backend URLs, e.g. {@code ["http://realtime-0:8077",
     * "http://realtime-1:8077"]}. Order does not matter; the hash function
     * assigns users deterministically across the set.
     */
    private List<String> realtimeInstances = List.of();

    public void setRealtimeInstances(List<String> instances) {
        this.realtimeInstances = instances != null ? instances : List.of();
    }

    public List<String> getRealtimeInstances() {
        return realtimeInstances;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        if (!path.startsWith("/api/v1/live/")) {
            return chain.filter(exchange);
        }

        String userId = extractUserId(path);
        if (userId == null || realtimeInstances.isEmpty()) {
            return chain.filter(exchange);
        }

        String backend = pickBackend(userId);
        ServerHttpRequest mutated = request.mutate()
                .uri(org.springframework.web.util.UriComponentsBuilder.fromUri(request.getURI())
                        .host(org.springframework.web.util.UriComponentsBuilder.fromUriString(backend).build().getHost())
                        .port(org.springframework.web.util.UriComponentsBuilder.fromUriString(backend).build().getPort())
                        .build()
                        .toUri())
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }

    private String extractUserId(String path) {
        // /api/v1/live/{userId}/...
        String[] segments = path.split("/");
        if (segments.length >= 5) {
            return segments[4];
        }
        return null;
    }

    private String pickBackend(String userId) {
        int index = Math.floorMod(userId.hashCode(), realtimeInstances.size());
        return realtimeInstances.get(index);
    }
}
