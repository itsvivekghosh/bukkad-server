package com.bhukkad.gateway.flags;

import com.bhukkad.common.security.PlatformJwtValidator;
import com.bhukkad.gateway.cache.EdgeCacheFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EdgeKillSwitchFilterTest {

    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            return Mono.empty();
        }
    }

    private static byte[] toByteArray(DataBuffer buf) {
        byte[] bytes = new byte[buf.readableByteCount()];
        buf.read(bytes);
        DataBufferUtils.release(buf);
        return bytes;
    }

    @SuppressWarnings("unchecked")
    private static void setWriteHandler(
            MockServerHttpResponse response,
            java.util.function.Function<? super reactor.core.publisher.Flux<DataBuffer>, ? extends reactor.core.publisher.Mono<Void>> handler) {
        try {
            Field f = MockServerHttpResponse.class
                    .getDeclaredField("writeHandler");
            f.setAccessible(true);
            f.set(response, handler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static MockServerWebExchange exchangeWithResponseCapture(
            MockServerWebExchange exchange, AtomicReference<byte[]> capturedOut) {
        MockServerHttpResponse mockResponse =
                (MockServerHttpResponse) exchange.getResponse();
        setWriteHandler(mockResponse, flux -> flux
                .doOnNext(buf -> capturedOut.set(toByteArray(buf)))
                .then(Mono.empty()));
        return exchange;
    }

    private static MockServerWebExchange exchangeWithMutableHeaders(MockServerWebExchange exchange) {
        MockServerHttpResponse mockResponse = (MockServerHttpResponse) exchange.getResponse();
        try {
            Field readOnlyHeadersField = org.springframework.http.server.reactive.AbstractServerHttpResponse.class
                    .getDeclaredField("readOnlyHeaders");
            readOnlyHeadersField.setAccessible(true);
            readOnlyHeadersField.set(mockResponse, new org.springframework.http.HttpHeaders());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PlatformJwtValidator> providerOf(PlatformJwtValidator validator) {
        ObjectProvider<PlatformJwtValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> redisProviderOf(
            ReactiveStringRedisTemplate redis) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterProviderOf(
            io.micrometer.core.instrument.MeterRegistry registry) {
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static EdgeKillSwitchFilter filterWith(EdgeFeatureFlags flags,
                                                    ObjectProvider<PlatformJwtValidator> jwtProvider,
                                                    ReactiveStringRedisTemplate redis,
                                                    SimpleMeterRegistry meters) {
        return new EdgeKillSwitchFilter(
                flags,
                jwtProvider,
                redisProviderOf(redis),
                meterProviderOf(meters),
                30);
    }

    private static MockServerWebExchange exchangeWithFlagRoute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("Authorization", "Bearer some.token"));
        Route route = Route.async()
                .id("flagged")
                .uri(URI.create("http://localhost:1"))
                .asyncPredicate(ex -> Mono.just(true))
                .metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.test.enabled")
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    private static MockServerWebExchange exchangeWithCacheableFlagRoute() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/restaurants/public?city=Bangalore")
                        .header("Authorization", "Bearer some.token"));
        Route route = Route.async()
                .id("flagged-cacheable")
                .uri(URI.create("http://localhost:1"))
                .asyncPredicate(ex -> Mono.just(true))
                .metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.restaurant.enabled")
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    @Test
    void subjectResolution_runsOffTheCallerThread_onBoundedElastic() {
        AtomicReference<String> workerThread = new AtomicReference<>();
        PlatformJwtValidator blockingValidator = mock(PlatformJwtValidator.class);
        when(blockingValidator.validate(any())).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            Thread.sleep(250);
            return Optional.empty();
        });
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(true));

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, providerOf(blockingValidator), null, meters);

        long t0 = System.nanoTime();
        Chain chain = new Chain();
        filter.filter(exchangeWithFlagRoute(), chain).block(Duration.ofSeconds(5));

        assertThat(chain.passed).isTrue();
        assertThat(workerThread.get()).isNotNull();
        assertThat(workerThread.get())
                .as("subjectId must not run on the caller/event-loop thread")
                .contains("boundedElastic");
        assertThat((System.nanoTime() - t0) / 1_000_000).isGreaterThanOrEqualTo(200);
    }

    @Test
    void flaglessRoute_passesThrough_withoutTokenWork() {
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, null, meters);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/plain"));
        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isTrue();
    }

    @Test
    void disabledFlag_answersMaintenanceEnvelope_withRetryAfter() {
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, null, meters);

        java.util.concurrent.atomic.AtomicReference<byte[]> capturedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        MockServerWebExchange baseExchange = exchangeWithFlagRoute();
        MockServerWebExchange exchange = exchangeWithResponseCapture(
                exchangeWithMutableHeaders(baseExchange), capturedBody);

        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(capturedBody.get()).isNotNull();
        String body = new String(capturedBody.get());
        assertThat(body).contains("SERVICE_DISABLED");
        assertThat(body).contains("edge.test.enabled");
    }

    @Test
    void disabledCacheableGet_cacheHit_servesCachedBody() {
        java.util.concurrent.atomic.AtomicReference<byte[]> capturedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        MockServerWebExchange baseExchange = exchangeWithCacheableFlagRoute();
        MockServerWebExchange exchange = exchangeWithResponseCapture(
                exchangeWithMutableHeaders(baseExchange), capturedBody);

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        org.springframework.data.redis.core.ReactiveValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.just("{\"items\":[\"cached\"]}"));

        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, redis, meters);

        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(capturedBody.get()).isNotNull();
        assertThat(new String(capturedBody.get())).contains("cached");
    }

    @Test
    void disabledCacheableGet_cacheMiss_servesEmptyArray() {
        java.util.concurrent.atomic.AtomicReference<byte[]> capturedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        MockServerWebExchange baseExchange = exchangeWithCacheableFlagRoute();
        MockServerWebExchange exchange = exchangeWithResponseCapture(
                exchangeWithMutableHeaders(baseExchange), capturedBody);

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        org.springframework.data.redis.core.ReactiveValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.empty());

        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, redis, meters);

        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(capturedBody.get()).isNotNull();
        assertThat(new String(capturedBody.get())).isEqualTo("[]");
    }

    @Test
    void disabledCacheableGet_redisError_fallsBackToEmptyArray() {
        java.util.concurrent.atomic.AtomicReference<byte[]> capturedBody =
                new java.util.concurrent.atomic.AtomicReference<>();
        MockServerWebExchange baseExchange = exchangeWithCacheableFlagRoute();
        MockServerWebExchange exchange = exchangeWithResponseCapture(
                exchangeWithMutableHeaders(baseExchange), capturedBody);

        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        org.springframework.data.redis.core.ReactiveValueOperations<String, String> valueOps =
                mock(org.springframework.data.redis.core.ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(Mono.error(new RuntimeException("redis down")));

        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, redis, meters);

        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
        assertThat(capturedBody.get()).isNotNull();
        assertThat(new String(capturedBody.get())).isEqualTo("[]");
    }

    @Test
    void disabledNonCacheableGet_returnsMaintenanceEnvelopeWithoutRedisLookup() {
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, null, meters);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders/123").header("Authorization", "Bearer some.token"));
        Route route = Route.async()
                .id("flagged-orders")
                .uri(URI.create("http://localhost:1"))
                .asyncPredicate(ex -> Mono.just(true))
                .metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.order.enabled")
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        exchange = exchangeWithMutableHeaders(exchange);

        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
    }

    @Test
    void disabledPost_returnsMaintenanceEnvelope() {
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, emptyProvider, null, meters);

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/orders").header("Authorization", "Bearer some.token"));
        Route route = Route.async()
                .id("flagged-post")
                .uri(URI.create("http://localhost:1"))
                .asyncPredicate(ex -> Mono.just(true))
                .metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.order.enabled")
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        exchange = exchangeWithMutableHeaders(exchange);

        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("30");
    }

    @Test
    void validatorBlowup_degradesToAnonymousDecision() {
        PlatformJwtValidator validator = mock(PlatformJwtValidator.class);
        when(validator.validate(any())).thenThrow(new IllegalStateException("JWKS down"));
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(true));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EdgeKillSwitchFilter filter = filterWith(flags, providerOf(validator), null, meters);

        Chain chain = new Chain();
        filter.filter(exchangeWithFlagRoute(), chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isTrue();
        verify(flags).isRouteEnabled(eq("edge.test.enabled"), isNull());
    }
}
