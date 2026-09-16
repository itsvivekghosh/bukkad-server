package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EdgeSizeLimitFilter}: rejects oversized requests,
 * passes normal traffic, and respects configured limits.
 */
class EdgeSizeLimitFilterTest {

    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();
        final AtomicReference<ServerWebExchange> exchangeRef = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            exchangeRef.set(exchange);
            return Mono.empty();
        }
    }

    private static EdgeSizeLimitFilter filter(long maxRequestBytes, long maxResponseBytes) {
        return new EdgeSizeLimitFilter(maxRequestBytes, maxResponseBytes);
    }

    @Test
    void requestExceedingLimit_returns413() {
        Chain chain = new Chain();
        EdgeSizeLimitFilter edgeFilter = filter(1024, 10 * 1024 * 1024);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/upload")
                        .header("Content-Length", "2048"));

        edgeFilter.filter(exchange, chain).block();

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void requestWithinLimit_passesThrough() {
        Chain chain = new Chain();
        EdgeSizeLimitFilter edgeFilter = filter(1024, 10 * 1024 * 1024);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/upload")
                        .header("Content-Length", "512"));

        edgeFilter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void getRequest_withoutContentLength_passesThrough() {
        Chain chain = new Chain();
        EdgeSizeLimitFilter edgeFilter = filter(1024, 10 * 1024 * 1024);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public"));

        edgeFilter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void chunkedRequest_passesThrough() {
        Chain chain = new Chain();
        EdgeSizeLimitFilter edgeFilter = filter(1024, 10 * 1024 * 1024);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/upload")
                        .header("Transfer-Encoding", "chunked"));

        edgeFilter.filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void defaultLimits_usedWhenZeroProvided() {
        Chain chain = new Chain();
        EdgeSizeLimitFilter edgeFilter = filter(0, 0);

        // Default max-request is 2MB; send 5MB to trigger 413
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/upload")
                        .header("Content-Length", String.valueOf(5L * 1024 * 1024)));

        edgeFilter.filter(exchange, chain).block();

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }
}
