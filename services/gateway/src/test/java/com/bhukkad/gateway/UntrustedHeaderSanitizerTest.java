package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Untrusted header hygiene: internal identity/routing headers injected by
 * callers must never reach services behind the edge.
 */
class UntrustedHeaderSanitizerTest {

    private static final UntrustedHeaderSanitizer FILTER = new UntrustedHeaderSanitizer();

    private static final List<String> UNTRUSTED = List.of(
            "X-Service-Token", "X-Customer-Id", "X-Forwarded-Host", "X-Forwarded-Proto",
            "X-Forwarded-Port", "X-Forwarded-Prefix", "X-Original-URL", "X-Rewrite-URL");

    private static final class CapturingChain implements GatewayFilterChain {
        final AtomicReference<ServerWebExchange> seen = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            seen.set(exchange);
            return Mono.empty();
        }
    }

    @Test
    void stripsEveryUntrustedHeader_andKeepsTrustedOnes() {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/api/v1/orders");
        UNTRUSTED.forEach(h -> request.header(h, "attacker-value"));
        request.header("X-Request-Id", "trace-keep");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        CapturingChain chain = new CapturingChain();
        FILTER.filter(exchange, chain).block();

        HttpHeaders downstream = chain.seen.get().getRequest().getHeaders();
        UNTRUSTED.forEach(h -> assertThat(downstream.containsKey(h)).as(h).isFalse());
        assertThat(downstream.getFirst("X-Request-Id")).isEqualTo("trace-keep");
    }

    @Test
    void multiValuedHeader_isRemovedEntirelyNotJustFirstValue() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("X-Customer-Id", "1", "2"));
        CapturingChain chain = new CapturingChain();

        FILTER.filter(exchange, chain).block();

        assertThat(chain.seen.get().getRequest().getHeaders().get("X-Customer-Id")).isNull();
    }

    @Test
    void cleanRequest_flowsThroughUnwrappingNoExchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/x"));
        CapturingChain chain = new CapturingChain();

        FILTER.filter(exchange, chain).block();

        assertThat(chain.seen.get()).isSameAs(exchange);
    }

    @Test
    void orderIsBeforeUpstreamRouting() {
        assertThat(UntrustedHeaderSanitizer.ORDER).isLessThan(0);
    }
}
