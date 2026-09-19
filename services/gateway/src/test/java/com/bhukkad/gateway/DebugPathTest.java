package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;

class DebugPathTest {
    @Test
    void debugPath() {
        var request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(InetSocketAddress.createUnresolved("10.0.0.1", 1));
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        System.out.println("Method: " + exchange.getRequest().getMethod());
        System.out.println("Path: " + exchange.getRequest().getPath());
        System.out.println("Path value: " + exchange.getRequest().getPath().value());
    }
}
