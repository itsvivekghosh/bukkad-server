package com.bhukkad.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;

class DebugBodyTest {
    @Test
    void debugBody() {
        byte[] bytes = "{\"email\":\"user@example.com\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(InetSocketAddress.createUnresolved("10.0.0.1", 1))
                .body(Flux.just(new DefaultDataBufferFactory().wrap(bytes)));
        ServerWebExchange exchange = MockServerWebExchange.from(request);
        
        exchange.getRequest().getBody()
                .map(dataBuffer -> {
                    byte[] arr = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(arr);
                    org.springframework.core.io.buffer.DataBufferUtils.release(dataBuffer);
                    return new String(arr, java.nio.charset.StandardCharsets.UTF_8);
                })
                .doOnNext(s -> System.out.println("BODY: " + s))
                .blockLast();
    }
}
