package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class DebugSetCompleteTest {
    @Test
    void debugSetComplete() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                org.springframework.mock.http.server.reactive.MockServerHttpRequest.get("/test").build());
        Mono<Void> m = exchange.getResponse().setComplete();
        System.out.println("SET_COMPLETE_MONO: " + m);
        long start = System.currentTimeMillis();
        m.block();
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("ELAPSED: " + elapsed + "ms");
    }
}
