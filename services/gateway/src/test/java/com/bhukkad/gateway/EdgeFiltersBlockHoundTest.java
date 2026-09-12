package com.bhukkad.gateway;

import com.bhukkad.common.security.PlatformJwtValidator;
import com.bhukkad.gateway.flags.EdgeFeatureFlags;
import com.bhukkad.gateway.flags.EdgeKillSwitchFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.blockhound.BlockHound;
import reactor.blockhound.BlockingOperationError;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P1 REACTOR-HYGIENE (BlockHound): the edge filters run on Netty event-loop
 * threads in production, where ANY blocking call is a starvation bug (audit
 * V-13/V-16). These proofs exercise {@link EdgeRateLimitFilter} and
 * {@link EdgeKillSwitchFilter} subscribed on a NON-BLOCKING Reactor scheduler
 * — the same guarantee {@code reactor-http-nio} threads carry — asserting no
 * blocking call escapes into the loop segment. Both filters deliberately hop
 * JWT/subject work to boundedElastic (V-13); validators here really sleep, so
 * any future inlining onto the guarded segment turns into a failed test
 * instead of a frozen edge. A negative control proves the instrumentation is
 * actually armed in this fork.
 *
 * <p>Gating: the gateway pom activates
 * {@code -XX:+AllowRedefinitionToAddDeleteMethods -Dblockhound.armed=true}
 * via the JDK-range profile {@code blockhound-jvm-flags}; without the arm
 * property this class ASSUMPTION-SKIPS (installing BlockHound without the
 * flag leaves partially redefined classes that poison the fork). Escape
 * hatch if the integration itself misbehaves: {@code -DexcludedGroups=blockhound}.</p>
 */
@Tag("blockhound")
class EdgeFiltersBlockHoundTest {

    private static final Duration GUARD = Duration.ofSeconds(10);

    /**
     * Warm-up: force Mockito's inline mock-maker (ByteBuddy plugin,
     * Instrumentation hooks) to fully initialise on the MAIN thread BEFORE
     * BlockHound arms instrumentation — lazy Mockito bootstrap landing on an
     * instrumented worker breaks the mock-maker for the whole fork.
     */
    @SuppressWarnings("unused")
    private static final Runnable MOCKITO_WARMUP = Mockito.mock(Runnable.class);

    @BeforeAll
    static void installBlockHound() {
        // Reactor's service-loaded BlockHound integration marks parallel()
        // and single() workers NON-BLOCKING (boundedElastic is blocking-
        // tolerant by contract — exactly the offload V-13 relies on).
        Assumptions.assumeTrue(Boolean.getBoolean("blockhound.armed"),
                "BlockHound needs -XX:+AllowRedefinitionToAddDeleteMethods — armed"
                        + " automatically by the gateway blockhound-jvm-flags profile (JDK 13–21).");
        BlockHound.install();
    }

    /** Control: BlockHound must actually bite in this fork, or all green here is worthless. */
    @Test
    void negativeControl_blockingSleepOnNonBlockingScheduler_isDetected() {
        Throwable failure = catchThrowable(() -> Mono.fromCallable(() -> {
            Thread.sleep(20);
            return "never";
        }).subscribeOn(Schedulers.parallel()).block(GUARD));

        // The raw BlockingOperationError raised on the worker surfaces
        // through block() wrapped — assert on the whole cause chain.
        assertThat(failure)
                .as("BlockHound must flag Thread.sleep on a parallel worker")
                .hasRootCauseInstanceOf(BlockingOperationError.class)
                .hasMessageContaining("Blocking call");
    }

    // ─── EdgeRateLimitFilter ───────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static EdgeRateLimitFilter rateLimitFilter(ReactiveStringRedisTemplate redis,
                                                       PlatformJwtValidator validator) {
        ObjectProvider redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        ObjectProvider meterProvider = mock(ObjectProvider.class);
        when(meterProvider.getIfAvailable()).thenReturn(new SimpleMeterRegistry());
        ObjectProvider jwtProvider = mock(ObjectProvider.class);
        when(jwtProvider.getIfAvailable()).thenReturn(validator);
        return new EdgeRateLimitFilter(redisProvider, meterProvider, jwtProvider, true, true);
    }

    private static ReactiveStringRedisTemplate redis(Flux<Long> verdict) {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyList())).thenReturn(verdict);
        return redis;
    }

    private static MockServerWebExchange loginRequest() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/auth/login")
                .remoteAddress(new InetSocketAddress("10.0.0.9", 51111)));
    }

    private static final class Chain implements GatewayFilterChain {
        final AtomicBoolean passed = new AtomicBoolean();
        final AtomicReference<String> workerThread = new AtomicReference<>();

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            workerThread.set(Thread.currentThread().getName());
            passed.set(true);
            return Mono.empty();
        }
    }

    @Test
    void allowedLogin_noBlockingWorkOnTheEventLoopSegment() {
        // EVERY mock construction lives on the calling MAIN thread here:
        // lazily initialising Mockito inline classes on an instrumented
        // worker breaks the mock-maker for the whole fork.
        EdgeRateLimitFilter filter = rateLimitFilter(redis(Flux.just(1L)), null);
        MockServerWebExchange exchange = loginRequest();
        Chain chain = new Chain();

        assertThatCode(() -> Mono.defer(() -> filter.filter(exchange, chain))
                .subscribeOn(Schedulers.parallel())
                .block(GUARD))
                .as("rate-limit pass path must never block")
                .doesNotThrowAnyException();

        assertThat(chain.passed).isTrue();
    }

    @Test
    void allowedLogin_withSleepingJwtValidator_offloadIsVerified() {
        // bucketIdentifier() parses the bearer JWT — V-13 policy: only on
        // boundedElastic. A validator that really sleeps turns any future
        // inlining onto the loop segment into a BlockHound failure.
        PlatformJwtValidator slowValidator = mock(PlatformJwtValidator.class);
        when(slowValidator.validate(anyString())).thenAnswer(invocation -> {
            Thread.sleep(60); // stands in for JWKS fetch / RSA work
            return Optional.empty();
        });
        EdgeRateLimitFilter filter = rateLimitFilter(redis(Flux.just(1L)), slowValidator);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .header("Authorization", "Bearer some.token")
                        .remoteAddress(new InetSocketAddress("10.0.0.9", 51111)));
        Chain chain = new Chain();

        assertThatCode(() -> Mono.defer(() -> filter.filter(exchange, chain))
                .subscribeOn(Schedulers.parallel())
                .block(GUARD))
                .as("subject/JWT work may only run on the blocking-tolerant scheduler")
                .doesNotThrowAnyException();

        assertThat(chain.passed).isTrue();
        assertThat(chain.workerThread.get()).startsWith("boundedElastic-");
    }

    @Test
    void deniedLogin_429Write_noBlockingWork() {
        EdgeRateLimitFilter filter = rateLimitFilter(redis(Flux.just(-2500L)), null);
        MockServerWebExchange exchange = loginRequest();
        Chain chain = new Chain();

        assertThatCode(() -> Mono.defer(() -> filter.filter(exchange, chain))
                .subscribeOn(Schedulers.parallel())
                .block(GUARD))
                .as("denied path (Retry-After + JSON body write) must never block")
                .doesNotThrowAnyException();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void redisError_failsOpen_noBlockingWork() {
        ReactiveStringRedisTemplate broken = mock(ReactiveStringRedisTemplate.class);
        when(broken.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("connection refused")));
        EdgeRateLimitFilter filter = rateLimitFilter(broken, null);
        MockServerWebExchange exchange = loginRequest();
        Chain chain = new Chain();

        assertThatCode(() -> Mono.defer(() -> filter.filter(exchange, chain))
                .subscribeOn(Schedulers.parallel())
                .block(GUARD))
                .doesNotThrowAnyException();

        assertThat(chain.passed).as("fail-open passes the request (V-18 posture)").isTrue();
    }

    // ─── EdgeKillSwitchFilter (V-13 offload under instrumentation) ─────────────

    @SuppressWarnings("unchecked")
    private static ObjectProvider<PlatformJwtValidator> validatorProvider(
            PlatformJwtValidator validator) {
        ObjectProvider<PlatformJwtValidator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(validator);
        return provider;
    }

    /** A route with kill-switch metadata + bearer token, exactly as V-13 saw it. */
    private static MockServerWebExchange flaggedExchange() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/x").header("Authorization", "Bearer some.token"));
        Route route = Route.async()
                .id("flagged")
                .uri(java.net.URI.create("http://localhost:1"))
                .asyncPredicate(ex -> Mono.just(true))
                .metadata(EdgeKillSwitchFilter.ROUTE_FLAG_METADATA, "edge.test.enabled")
                .build();
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR, route);
        return exchange;
    }

    @Test
    void killSwitch_sleepingJwtValidator_offloadIsVerified() {
        PlatformJwtValidator slowValidator = mock(PlatformJwtValidator.class);
        when(slowValidator.validate(anyString())).thenAnswer(invocation -> {
            Thread.sleep(60); // stands in for JWKS fetch + parse
            return Optional.empty();
        });
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(true));
        EdgeKillSwitchFilter filter = new EdgeKillSwitchFilter(flags, validatorProvider(slowValidator));
        Chain chain = new Chain();

        assertThatCode(() -> Mono.defer(() -> filter.filter(flaggedExchange(), chain))
                .subscribeOn(Schedulers.parallel())
                .block(GUARD))
                .as("the sleeping validator may only be reached on boundedElastic (V-13)")
                .doesNotThrowAnyException();

        assertThat(chain.passed).isTrue();
        assertThat(chain.workerThread.get()).startsWith("boundedElastic-");
    }

    @Test
    void killSwitch_disabledFlag_503Write_noBlockingWork() {
        EdgeFeatureFlags flags = mock(EdgeFeatureFlags.class);
        when(flags.isRouteEnabled(anyString(), any())).thenReturn(Mono.just(false));
        EdgeKillSwitchFilter filter = new EdgeKillSwitchFilter(flags, validatorProvider(null));
        MockServerWebExchange exchange = flaggedExchange();

        assertThatCode(() -> Mono.defer(() -> filter.filter(exchange, new Chain()))
                .subscribeOn(Schedulers.parallel())
                .block(GUARD))
                .doesNotThrowAnyException();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(503);
    }
}
