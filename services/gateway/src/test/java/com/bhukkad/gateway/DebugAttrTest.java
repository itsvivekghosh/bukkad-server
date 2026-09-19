package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

class DebugAttrTest {
    @Test
    void debugAttr() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(InetSocketAddress.createUnresolved("10.0.0.1", 1))
                .build();
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put("test-key", "test-value");
        System.out.println("ATTR: " + exchange.getAttribute("test-key"));
        System.out.println("ATTR2: " + exchange.getAttributes().get("test-key"));
    }
}
