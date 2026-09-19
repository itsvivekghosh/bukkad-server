package com.bhukkad.gateway;

import com.bhukkad.common.ratelimit.RedisRateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EdgeAccountLockoutFilterTest {

    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            return Mono.empty();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EdgeAccountLockoutFilter filter(ReactiveStringRedisTemplate redis,
                                                   SimpleMeterRegistry meters,
                                                   boolean enabled) {
        ObjectProvider redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        return new EdgeAccountLockoutFilter(redisProvider, meterProvider, 5, 900, 900, enabled);
    }

    private static MockServerWebExchange loginRequest(String body) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(InetSocketAddress.createUnresolved("10.0.0.1", 1))
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getAttributes().put(EdgeAccountLockoutFilter.REQUEST_BODY_ATTR, body);
        return exchange;
    }

    private static ReactiveStringRedisTemplate redisReturning(Long scriptResult) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList()))
                .thenReturn(Flux.just(scriptResult));
        return redis;
    }

    @Test
    void insideLimit_passesThrough() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(redis, meters, true)
                .filter(loginRequest("{\"email\":\"user@example.com\",\"password\":\"pass\"}"), chain)
                .block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void overLimit_answers429_andDoesNotCallChain() {
        ReactiveStringRedisTemplate redis = redisReturning(-900_000L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();
        MockServerWebExchange exchange = loginRequest("{\"email\":\"user@example.com\",\"password\":\"pass\"}");

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("900");
    }

    @Test
    void redisError_failsOpen_passesThrough() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(redis, meters, true)
                .filter(loginRequest("{\"email\":\"user@example.com\",\"password\":\"pass\"}"), chain)
                .block();

        assertThat(chain.passed).isTrue();
        assertThat(meters.get("auth_edge_lockout_bypass").counter().count()).isEqualTo(1.0);
    }

    @Test
    void nonLoginPath_isNeverBlocked() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants")
                        .remoteAddress(InetSocketAddress.createUnresolved("10.0.0.1", 1))
                        .build());

        filter(redis, meters, true).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void disabledFilter_neverTouchesRedis() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        Chain chain = new Chain();

        filter(redis, new SimpleMeterRegistry(), false)
                .filter(loginRequest("{\"email\":\"user@example.com\",\"password\":\"pass\"}"), chain)
                .block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void missingEmailField_passesThrough() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(redis, meters, true)
                .filter(loginRequest("{\"password\":\"pass\"}"), chain)
                .block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void emailNormalizedToLowercase_inRedisKey() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);

        filter(redis, new SimpleMeterRegistry(), true)
                .filter(loginRequest("{\"email\":\"User@Example.COM\",\"password\":\"pass\"}"), new Chain())
                .block();

        org.mockito.ArgumentCaptor<List> keys = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), anyList());
        List<String> keyList = keys.getValue();
        assertThat(keyList).anySatisfy(k -> assertThat(k).contains("user@example.com"));
    }

    @Test
    void missingRedisBean_limiterIsInert() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(null, meters, true)
                .filter(loginRequest("{\"email\":\"user@example.com\",\"password\":\"pass\"}"), chain)
                .block();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void lockActivation_countsLockoutMetric() {
        ReactiveStringRedisTemplate redis = redisReturning(-900_000L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(redis, meters, true)
                .filter(loginRequest("{\"email\":\"user@example.com\",\"password\":\"pass\"}"), chain)
                .block();

        assertThat(meters.get("auth_edge_lockout_active").counter().count()).isEqualTo(1.0);
    }
}
