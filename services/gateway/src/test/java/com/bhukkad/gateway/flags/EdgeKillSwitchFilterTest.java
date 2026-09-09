package com.bhukkad.gateway.flags;

import com.bhukkad.common.security.PlatformJwtValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit V-13: {@code subjectId()} (which may block on JWT/JWKS work inside the
 * servlet-oriented validator) must NEVER run on the Netty event loop — the
 * filter defers it onto {@code boundedElastic}.
 */
class EdgeKillSwitchFilterTest {

    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            passed.set(true);
            return Mono.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PlatformJwtValidator> providerOf(PlatformJwtValidator validator) {
        ObjectProvider<PlatformJwtValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);
        return provider;
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

    @Test
    void subjectResolution_runsOffTheCallerThread_onBoundedElastic() {
        AtomicReference<String> workerThread = new AtomicReference<>();
        PlatformJwtValidator blockingValidator = mock(PlatformJwtValidator.class);
        when(blockingValidator.validate(any())).thenAnswer(invocation -> {
            workerThread.set(Thread.currentThread().getName());
            Thread.sleep(250); // stands in for a JWKS fetch / key parse
            return Optional.empty();
        });
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(true));

        EdgeKillSwitchFilter filter = new EdgeKillSwitchFilter(flags, providerOf(blockingValidator));

        long t0 = System.nanoTime();
        Chain chain = new Chain();
        filter.filter(exchangeWithFlagRoute(), chain).block(Duration.ofSeconds(5));

        // The blocked work moved off the caller (main) thread, onto elastic —
        // and the request still flows when the flag is enabled.
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
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        EdgeKillSwitchFilter filter = new EdgeKillSwitchFilter(flags, emptyProvider);

        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/plain"));
        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isTrue();
    }

    @Test
    void disabledFlag_answers503Envelope_withoutRouting() {
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformJwtValidator> emptyProvider = mock(ObjectProvider.class);
        when(emptyProvider.getIfAvailable()).thenReturn(null);
        EdgeKillSwitchFilter filter = new EdgeKillSwitchFilter(flags, emptyProvider);

        MockServerWebExchange exchange = exchangeWithFlagRoute();
        Chain chain = new Chain();
        filter.filter(exchange, chain).block(Duration.ofSeconds(2));

        assertThat(chain.passed).isFalse();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("SERVICE_DISABLED");
    }
}
