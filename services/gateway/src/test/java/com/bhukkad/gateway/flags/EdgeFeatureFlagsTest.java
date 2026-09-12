package com.bhukkad.gateway.flags;

import com.bhukkad.common.featureflag.FeatureFlagHash;
import com.bhukkad.common.featureflag.FeatureFlagProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.util.AbstractMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Edge kill-switch flag state: snapshot reload (fresh + fail-open),
 * override precedence, pub/sub staleness stamping, and lifecycle hooks.
 */
class EdgeFeatureFlagsTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReactiveStringRedisTemplate> providerOf(ReactiveStringRedisTemplate redis) {
        ObjectProvider<ReactiveStringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    private static void hashReturning(ReactiveStringRedisTemplate redis,
                                      Flux<Map.Entry<String, String>> entries) {
        ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
        when(hash.entries(EdgeFeatureFlags.OVERRIDES_HASH)).thenReturn(entries);
        Mockito.doReturn(hash).when(redis).opsForHash();
    }

    private static Map.Entry<String, String> entry(String k, String v) {
        return new AbstractMap.SimpleImmutableEntry<>(k, v);
    }

    @Test
    void noRedis_decidesFromPrimesAndConfigDefaults() {
        FeatureFlagProperties props = new FeatureFlagProperties();
        EdgeFeatureFlags flags = new EdgeFeatureFlags(providerOf(null), providerOf(null), props);
        flags.subscribeInvalidations(); // relay absent: defaults-only mode, no error

        props.getFlags().put("configured.off", false);

        flags.primeOverride("killed.route", "false");
        StepVerifier.create(flags.isRouteEnabled("killed.route", null))
                .expectNext(false).verifyComplete();
        assertThat(flags.evaluateNow("killed.route", null)).isFalse();

        // configured-default flag consulted when no override exists
        StepVerifier.create(flags.isRouteEnabled("configured.off", null))
                .expectNext(false).verifyComplete();

        // unknown flag: fail-open enabled
        StepVerifier.create(flags.isRouteEnabled("unknown.route", null))
                .expectNext(true).verifyComplete();

        flags.closeSubscription(); // never subscribed: no-op
    }

    @Test
    void primeOverride_removeRestoresDefaultAndRefreshesStamp() {
        EdgeFeatureFlags flags = new EdgeFeatureFlags(
                providerOf(null), providerOf(null), new FeatureFlagProperties());
        flags.primeOverride("a", "false");
        assertThat(flags.evaluateNow("a", null)).isFalse();
        flags.primeOverride("a", null);
        assertThat(flags.evaluateNow("a", null)).isTrue();
    }

    @Test
    void rolloutBucketsKeepEdgeAndServiceDecisionsConsistent() {
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getRollout().put("percent.route", 50);
        EdgeFeatureFlags flags = new EdgeFeatureFlags(providerOf(null), providerOf(null), props);

        assertThat(flags.evaluateNow("percent.route", 42L))
                .isEqualTo(FeatureFlagHash.bucket("percent.route", 42L) < 50);
    }

    @Test
    void staleSnapshot_reloadsFromRedisHash() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        hashReturning(redis,
                Flux.just(entry("edge.kill", "false"), entry("edge.keep", "true")));
        EdgeFeatureFlags flags = new EdgeFeatureFlags(
                providerOf(redis), providerOf(null), new FeatureFlagProperties());

        // NOTE (suspected src/main bug): isRouteEnabled() passes evaluateNow(...)
        // eagerly into thenReturn(), so the decision that TRIGGERS the reload is
        // computed from the pre-reload snapshot. The refreshed state starts
        // serving on the next call. Behavior asserted as-is.
        StepVerifier.create(flags.isRouteEnabled("edge.kill", null))
                .expectNext(true).verifyComplete(); // stale decision, reload lands
        StepVerifier.create(flags.isRouteEnabled("edge.kill", null))
                .expectNext(false).verifyComplete(); // fresh snapshot
        StepVerifier.create(flags.isRouteEnabled("edge.keep", null))
                .expectNext(true).verifyComplete();
        // flag absent from the fresh hash falls back to default (enabled)
        StepVerifier.create(flags.isRouteEnabled("other", null))
                .expectNext(true).verifyComplete();
    }

    @Test
    void pubSubMessage_stampsSnapshotStale_andReloadDropsMissingKeys() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ReactiveHashOperations<String, String, String> hash = mock(ReactiveHashOperations.class);
        when(hash.entries(EdgeFeatureFlags.OVERRIDES_HASH))
                .thenReturn(Flux.just(entry("edge.kill", "false")))
                .thenReturn(Flux.just(entry("edge.other", "false")));
        Mockito.doReturn(hash).when(redis).opsForHash();

        ReactiveStringRedisTemplate relay = mock(ReactiveStringRedisTemplate.class);
        Sinks.Many<ReactiveSubscription.Message<String, String>> events = Sinks.many().unicast().onBackpressureBuffer();
        Mockito.doReturn(events.asFlux()).when(relay).listenToChannel(EdgeFeatureFlags.CHANGED_CHANNEL);

        EdgeFeatureFlags flags = new EdgeFeatureFlags(providerOf(redis), providerOf(relay), new FeatureFlagProperties());
        flags.subscribeInvalidations();

        // reload-triggering decision comes from the pre-reload snapshot (see
        // suspected bug note in staleSnapshot_reloadsFromRedisHash)
        StepVerifier.create(flags.isRouteEnabled("edge.kill", null))
                .expectNext(true).verifyComplete();
        StepVerifier.create(flags.isRouteEnabled("edge.kill", null))
                .expectNext(false).verifyComplete();

        @SuppressWarnings("unchecked")
        ReactiveSubscription.Message<String, String> change = mock(ReactiveSubscription.Message.class);
        events.tryEmitNext(change); // reload stamped stale

        StepVerifier.create(flags.isRouteEnabled("edge.kill", null))
                .expectNext(false).verifyComplete(); // pre-reload value; fresh lands now
        StepVerifier.create(flags.isRouteEnabled("edge.other", null))
                .expectNext(false).verifyComplete(); // arrived with the reload
        StepVerifier.create(flags.isRouteEnabled("edge.kill", null))
                .expectNext(true).verifyComplete(); // dropped by retainAll vs fresh hash

        flags.closeSubscription();
    }

    @Test
    void redisFailure_failsOpenKeepingLastSnapshot() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        hashReturning(redis, Flux.error(new RuntimeException("connection refused")));
        EdgeFeatureFlags flags = new EdgeFeatureFlags(
                providerOf(redis), providerOf(null), new FeatureFlagProperties());

        StepVerifier.create(flags.isRouteEnabled("anything", null))
                .expectNext(true).verifyComplete(); // fail-open: defaults keep serving
    }

    @Test
    void subscribeAndDispose_lifecycleOnRelayTemplate() {
        ReactiveStringRedisTemplate relay = mock(ReactiveStringRedisTemplate.class);
        Mockito.doReturn(Flux.never()).when(relay).listenToChannel(EdgeFeatureFlags.CHANGED_CHANNEL);
        EdgeFeatureFlags flags = new EdgeFeatureFlags(
                providerOf(null), providerOf(relay), new FeatureFlagProperties());

        flags.subscribeInvalidations();
        flags.closeSubscription(); // disposes the live subscription
    }

    @Test
    void subscribeFailure_isSwallowed() {
        ReactiveStringRedisTemplate relay = mock(ReactiveStringRedisTemplate.class);
        Mockito.doThrow(new IllegalStateException("boom"))
                .when(relay).listenToChannel(EdgeFeatureFlags.CHANGED_CHANNEL);
        EdgeFeatureFlags flags = new EdgeFeatureFlags(
                providerOf(null), providerOf(relay), new FeatureFlagProperties());

        flags.subscribeInvalidations(); // must not propagate
        flags.closeSubscription();
    }
}
