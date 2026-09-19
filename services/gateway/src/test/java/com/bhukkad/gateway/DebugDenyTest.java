package com.bhukkad.gateway;

import com.bhukkad.common.ratelimit.RedisRateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebugDenyTest {
    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();
        int callCount = 0;

        @Override
        public Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
            callCount++;
            System.out.println("CHAIN_CALLED: count=" + callCount + " status=" + exchange.getResponse().getStatusCode());
            passed.set(true);
            return Mono.empty();
        }
    }

    @Test
    void debugDeny() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(-900_000L));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .remoteAddress(InetSocketAddress.createUnresolved("10.0.0.1", 1))
                        .build());
        exchange.getAttributes().put(EdgeAccountLockoutFilter.REQUEST_BODY_ATTR,
                "{\"email\":\"user@example.com\",\"password\":\"pass\"}");

        ObjectProvider<ReactiveStringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);

        EdgeAccountLockoutFilter filter = new EdgeAccountLockoutFilter(
                redisProvider, meterProvider, 5, 900, 900, true);

        // Debug the callable
        String host = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getHostString() : "null";
        System.out.println("DEBUG_REMOTE_ADDR: " + exchange.getRequest().getRemoteAddress());
        System.out.println("DEBUG_HOST_STRING: " + host);

        Mono<Void> result = filter.filter(exchange, chain);
        System.out.println("RESULT_MONO: " + result);
        result.block();
        System.out.println("FINAL: chain.callCount=" + chain.callCount + " passed=" + chain.passed
                + " status=" + exchange.getResponse().getStatusCode());
    }
}
