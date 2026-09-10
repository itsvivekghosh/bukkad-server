package com.bhukkad.gateway;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.LettuceClientConfigurationBuilder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis client-pool topology for the gateway edge (audit R-07/R-11
 * blast-radius split): ONE Redis, TWO Lettuce {@link ClientResources} pools.
 *
 * <p><b>Why two pools:</b> the edge runs a per-request limiter
 * ({@link EdgeRateLimitFilter}) and a long-lived pub/sub relay subscriber
 * ({@link com.bhukkad.gateway.flags.EdgeFeatureFlags}, whose flag
 * invalidations gate the live/SSE routes) against the same Redis. Sharing one
 * thread group means a saturated event loop can wedge the subscriber, and a
 * wedged subscriber can leak pressure back into the request path. The
 * dedicated small pool isolates the relay: it gets its own I/O threads, so
 * neither side can starve the other.</p>
 *
 * <p><b>Why the factories are declared here:</b> a second
 * {@link RedisConnectionFactory} bean makes Spring Boot's connection
 * auto-configuration back off ({@code @ConditionalOnMissingBean}), so both the
 * shared (primary) and the SSE-relay factory are wired explicitly. The
 * shared factory still honours every {@link
 * LettuceClientConfigurationBuilderCustomizer} (the {@code
 * sharedRedisPoolCustomizer} bean below plus any future ones) — the SPI the
 * audit prescribed for pool tuning.</p>
 *
 * <p>The SSE-relay side exposes {@code sseRelayReactiveStringRedisTemplate}:
 * the relay ({@code EdgeFeatureFlags#subscribeInvalidations}) must subscribe
 * on THAT template, never on the primary, or the isolation is void (G-2:
 * an unmounted isolation device counts as absent).</p>
 */
@Configuration
@EnableConfigurationProperties(GatewayRedisPoolProperties.class)
public class GatewayRedisClientConfig {

    /** Lettuce requires at least 2 threads per group. */
    static final int MIN_POOL_THREADS = 2;

    // ------------------------------------------------------------------
    // ClientResources (thread groups shared by every connection of a pool).
    // ------------------------------------------------------------------

    /** Request-path pool: limiter buckets + flag-hash refreshes. */
    @Bean(destroyMethod = "shutdown")
    public ClientResources gatewaySharedRedisClientResources(GatewayRedisPoolProperties props) {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(clamp(props.getShared().getIoThreads()))
                .computationThreadPoolSize(clamp(props.getShared().getComputationThreads()))
                .build();
    }

    /** Dedicated small relay pool: long-lived pub/sub subscribers only. */
    @Bean(destroyMethod = "shutdown")
    public ClientResources sseRelayRedisClientResources(GatewayRedisPoolProperties props) {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(clamp(props.getSseRelay().getIoThreads()))
                .computationThreadPoolSize(clamp(props.getSseRelay().getComputationThreads()))
                .build();
    }

    /**
     * Applies the shared ClientResources to the primary connection factory via
     * the Lettuce customizer SPI (audit-mandated hook — also honoured by any
     * future Boot-managed factory tuning).
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer sharedRedisPoolCustomizer(
            ClientResources gatewaySharedRedisClientResources) {
        return builder -> builder.clientResources(gatewaySharedRedisClientResources);
    }

    // ------------------------------------------------------------------
    // Connection factories (auto-config is off — see class javadoc).
    // ------------------------------------------------------------------

    /** Primary shared factory: limiter + flag refresh + generic edge Redis. */
    @Bean
    @Primary
    public LettuceConnectionFactory redisConnectionFactory(
            RedisProperties redis,
            ObjectProvider<LettuceClientConfigurationBuilderCustomizer> customizers) {
        LettuceClientConfigurationBuilder builder = LettuceClientConfiguration.builder()
                .commandTimeout(commandTimeout(redis));
        customizers.orderedStream().forEach(c -> c.customize(builder));
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getPort()),
                builder.build());
    }

    /**
     * Shared reactive template (PRIMARY — supersedes Boot's auto-configured
     * bean of the same name). Without an explicit primary, every
     * by-type injection point would face the two-bean ambiguity the relay
     * side introduces (audit G-2: an unmounted isolation device is absent).
     */
    @Bean
    @Primary
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            LettuceConnectionFactory redisConnectionFactory) {
        return new ReactiveStringRedisTemplate(
                Objects.requireNonNull(redisConnectionFactory));
    }

    /** Dedicated relay factory — the ONLY factory the SSE relay may use. */
    @Bean(name = "sseRelayRedisConnectionFactory")
    public LettuceConnectionFactory sseRelayRedisConnectionFactory(
            RedisProperties redis,
            ClientResources sseRelayRedisClientResources) {
        LettuceClientConfiguration configuration = LettuceClientConfiguration.builder()
                .clientResources(sseRelayRedisClientResources)
                .commandTimeout(commandTimeout(redis))
                .build();
        return new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(redis.getHost(), redis.getPort()),
                configuration);
    }

    private static java.time.Duration commandTimeout(RedisProperties redis) {
        Duration timeout = redis.getTimeout();
        return timeout != null && !timeout.isZero() && !timeout.isNegative()
                ? timeout
                : Duration.ofSeconds(2);
    }

    /**
     * The relay's template. Wired by name (a plain {@code
     * ObjectProvider<ReactiveStringRedisTemplate>} injection would resolve the
     * primary/shared template and silently void the isolation — the relay must
     * qualify this bean).
     */
    @Bean(name = "sseRelayReactiveStringRedisTemplate")
    public ReactiveStringRedisTemplate sseRelayReactiveStringRedisTemplate(
            @Qualifier("sseRelayRedisConnectionFactory") ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(Objects.requireNonNull(factory));
    }

    private static int clamp(int threads) {
        return Math.max(MIN_POOL_THREADS, threads);
    }
}
