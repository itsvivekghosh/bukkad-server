package com.bhukkad.gateway;

import com.bhukkad.common.ratelimit.RedisRateLimitService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
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

/**
 * Unit tests for the edge Redis bucket limiter (V-18/PERF-1.3): atomic script
 * reuse, 429 + Retry-After, and fail-open on Redis errors.
 */
class EdgeRateLimitFilterTest {

    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            return Mono.empty();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EdgeRateLimitFilter filter(ReactiveStringRedisTemplate redis,
                                              SimpleMeterRegistry meters,
                                              boolean enabled,
                                              boolean failOpen) {
        ObjectProvider redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(meters);
        ObjectProvider jwtProvider = mock(ObjectProvider.class);
        when(jwtProvider.getIfAvailable()).thenReturn(null);
        return new EdgeRateLimitFilter(redisProvider, meterProvider, jwtProvider, enabled, failOpen);
    }

    private static MockServerWebExchange loginRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 51111)));
    }

    private static ReactiveStringRedisTemplate redisReturning(Long scriptResult) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.just(scriptResult));
        return redis;
    }

    @Test
    void insideLimit_passesThrough_andCountsAllowed() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(redis, meters, true, true).filter(loginRequest(), chain).block();

        assertThat(chain.passed).isTrue();
        assertThat(meters.get("ratelimit_allowed").tag("bucket", "edge-login").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void overLimit_answers429_withRetryAfter_envelopeAndDeniedCounter() {
        ReactiveStringRedisTemplate redis = redisReturning(-2500L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();
        MockServerWebExchange exchange = loginRequest();

        filter(redis, meters, true, true).filter(exchange, chain).block();

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("3");
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("RATE_LIMITED").contains("429");
        assertThat(meters.get("ratelimit_denied").tag("bucket", "edge-login").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void redisError_failsOpen_andCountsBypass() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();

        filter(redis, meters, true, true).filter(loginRequest(), chain).block();

        assertThat(chain.passed).isTrue();
        assertThat(meters.get(RedisRateLimitService.METRIC_BYPASS_REDIS_ERROR)
                .tag("bucket", "edge-login").counter().count()).isEqualTo(1.0);
    }

    @Test
    void redisError_failClosed_answers429() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        Chain chain = new Chain();
        MockServerWebExchange exchange = loginRequest();

        filter(redis, meters, true, false).filter(exchange, chain).block();

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void bucketIdentifier_carriesSubjectOrAnonAndClientIp() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        filter(redis, new SimpleMeterRegistry(), true, true).filter(loginRequest(), new Chain()).block();

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), anyList());
        // No registered validator bean at this edge ⇒ anon|<socket-ip> (no XFF trust).
        assertThat(keys.getValue().get(0))
                .isEqualTo(RedisRateLimitService.PREFIX + "edge-login:anon|10.0.0.9");
    }

    @Test
    void unlistedPath_isPassedThrough_withoutAnyRedisCall() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        Chain chain = new Chain();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/7").remoteAddress(InetSocketAddress.createUnresolved("10.0.0.9", 1)));

        filter(redis, new SimpleMeterRegistry(), true, true).filter(exchange, chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void disabledFilter_neverTouchesRedis() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        Chain chain = new Chain();

        filter(redis, new SimpleMeterRegistry(), false, true).filter(loginRequest(), chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

    @Test
    void webhookAndOrderCreatePathsAreCoveredByRules() {
        for (String path : List.of("/api/v1/payments/webhooks/razorpay", "/api/v1/orders",
                "/api/v1/customers/5/orders")) {
            ReactiveStringRedisTemplate redis = redisReturning(1L);
            Chain chain = new Chain();
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post(path).remoteAddress(InetSocketAddress.createUnresolved("10.0.0.9", 1)));

            filter(redis, new SimpleMeterRegistry(), true, true).filter(exchange, chain).block();

            assertThat(chain.passed).as(path).isTrue();
            verify(redis).execute(any(RedisScript.class), anyList(), anyList());
        }
    }

    @Test
    void loginGET_isNotRateLimited_onlyTheLoginPOSTIs() {
        ReactiveStringRedisTemplate redis = redisReturning(1L);
        Chain chain = new Chain();
        MockServerWebExchange get = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/auth/login"));

        filter(redis, new SimpleMeterRegistry(), true, true).filter(get, chain).block();

        assertThat(chain.passed).isTrue();
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyList());
    }

}
