package com.bhukkad.gateway.flags;

import com.bhukkad.common.security.PlatformJwtValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BlockHound proof (P1, V-13): the kill switch's {@code subjectId()} — which
 * performs the potentially blocking JWT/JWKS validation — must NEVER execute
 * on a Reactor non-blocking thread. The pipeline is subscribed on
 * {@code Schedulers.parallel()} (the closest event-loop stand-in); with
 * BlockHound installed, a blocking call anywhere on that thread would fail
 * the step with a {@link BlockingOperationError}.
 *
 * <p>Companion to {@link EdgeKillSwitchFilterTest}, which pins the offload to
 * {@code boundedElastic} by thread name; this test adds an independent,
 * instrumentation-based guarantee.</p>
 */
class EdgeKillSwitchFilterBlockHoundTest {

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

    @BeforeAll
    static void installBlockHound() {
        // Installed exactly once per surefire fork (see the module's agent
        // preload); a second install call is a no-op.
        BlockHoundInstaller.installOnce();
    }

    @Test
    void subjectIdOffload_neverBlocksTheNonBlockingThread() {
        PlatformJwtValidator blockingValidator = mock(PlatformJwtValidator.class);
        when(blockingValidator.validate(any())).thenAnswer(invocation -> {
            Thread.sleep(100); // stands in for a JWKS fetch — blocking, allowed ONLY on boundedElastic
            return Optional.empty();
        });
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(true));

        EdgeKillSwitchFilter filter = new EdgeKillSwitchFilter(flags, providerOf(blockingValidator));
        Chain chain = new Chain();

        reactor.test.StepVerifier.create(
                        filter.filter(exchangeWithFlagRoute(), chain)
                                .subscribeOn(Schedulers.parallel()))
                .verifyComplete();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void harnessGuard_blockHoundDetectsBlockingCallOnNonBlockingThread() {
        reactor.test.StepVerifier.create(Mono.fromCallable(() -> {
                    Thread.sleep(10);
                    return 1;
                }).subscribeOn(Schedulers.parallel()))
                .expectError(BlockingOperationError.class)
                .verify();
    }

    /** Per-module install-once guard (each surefire fork has its own JVM). */
    private static final class BlockHoundInstaller {
        private static final AtomicBoolean INSTALLED = new AtomicBoolean();

        static void installOnce() {
            if (INSTALLED.compareAndSet(false, true)) {
                BlockHound.install();
            }
        }
    }
}
