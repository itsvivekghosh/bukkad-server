package com.bhukkad.gateway.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EdgeCacheFilter}: cache hit short-circuits upstream,
 * cache miss falls through, non-GET/public paths bypass cache entirely.
 */
class EdgeCacheFilterTest {

    private static class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();
        final AtomicReference<ServerWebExchange> exchangeRef = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            exchangeRef.set(exchange);
            return Mono.empty();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EdgeCacheFilter filter(ReactiveStringRedisTemplate redis,
                                          SimpleMeterRegistry meters,
                                          boolean enabled) {
        ObjectProvider redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        return new EdgeCacheFilter(redisProvider, meterProvider, enabled, 60);
    }

    private static ReactiveStringRedisTemplate redisWithHit(String cachedBody) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.just(cachedBody));
        return redis;
    }

    private static ReactiveStringRedisTemplate redisWithMiss() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        return redis;
    }

    @Test
    void cacheHit_returnsCachedBody_andSkipsChain() {
        ReactiveStringRedisTemplate redis = redisWithHit("{\"items\":[]}");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));

        filter(redis, meters, true).filter(exchange, chain).block();

        // Verify Redis get was called (cache lookup happened)
        verify(redis.opsForValue()).get(anyString());
        // Cache hit must short-circuit the chain
        assertThat(chain.passed).as("chain must NOT execute on cache hit").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Edge-Cache")).isEqualTo("HIT");
    }

    @Test
    void cacheHit_withNewFormat_restoresActualStatusCode() {
        ReactiveStringRedisTemplate redis = redisWithHit("404|{\"status\":404,\"code\":\"NOT_FOUND\"}");
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore"));

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(chain.passed).as("chain must NOT execute on cache hit").isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange.getResponse().getHeaders().getFirst("X-Edge-Cache")).isEqualTo("HIT");
    }

    @Test
    void cacheMiss_passesThroughToChain() {
        ReactiveStringRedisTemplate redis = redisWithMiss();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public"));

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void cacheMiss_writesActualStatusCodeToCache() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveValueOperations<String, String> valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());
        when(valueOps.set(anyString(), anyString(), any(Duration.class)))
                .thenReturn(Mono.empty());

        ArgumentCaptor<String> setKeyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> setValueCaptor = ArgumentCaptor.forClass(String.class);

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain() {
            @Override
            public Mono<Void> filter(ServerWebExchange exchange) {
                passed.set(true);
                exchangeRef.set(exchange);
                ServerHttpResponse response = exchange.getResponse();
                response.setStatusCode(HttpStatus.NOT_FOUND);
                response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                DataBuffer buffer = response.bufferFactory().wrap("{\"status\":404,\"code\":\"NOT_FOUND\"}".getBytes(StandardCharsets.UTF_8));
                return response.writeWith(Mono.just(buffer));
            }
        };

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public"));

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(chain.passed).as("chain must execute on cache miss").isTrue();
        verify(valueOps).set(setKeyCaptor.capture(), setValueCaptor.capture(), any(Duration.class));
        assertThat(setValueCaptor.getValue()).startsWith("404|");
    }

    @Test
    void nonGetRequest_bypassesCache() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/restaurants/public"));

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    void nonCacheablePath_bypassesCache() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders/123"));

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    void cacheDisabled_passesThrough() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public"));

        filter(redis, meters, false).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).opsForValue();
    }

    @Test
    void redisUnavailable_passesThrough() {
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public"));

        filter(null, new SimpleMeterRegistry(), true).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void cacheKey_includesPathAndQueryParams() {
        ReactiveStringRedisTemplate redis = redisWithMiss();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/search?q=pizza&city=Bangalore"));

        filter(redis, meters, true).filter(exchange, chain).block();

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(redis.opsForValue()).get(keyCaptor.capture());
        assertThat(keyCaptor.getValue()).startsWith("edge:cache:GET:/api/v1/search");
        assertThat(keyCaptor.getValue()).contains("q=pizza");
        assertThat(keyCaptor.getValue()).contains("city=Bangalore");
    }
}
