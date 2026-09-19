package com.bhukkad.gateway;

import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Redis wiring units that the context tests leave untouched: command-timeout
 * fallback and the pool property accessors bound from config.
 */
class GatewayRedisClientConfigTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<LettuceClientConfigurationBuilderCustomizer> noCustomizers() {
        ObjectProvider<LettuceClientConfigurationBuilderCustomizer> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.empty());
        return provider;
    }

    @Test
    void missingOrNonPositiveRedisTimeout_fallsBackToTwoSeconds() {
        RedisProperties redis = new RedisProperties(); // default timeout: null
        LettuceConnectionFactory factory = new GatewayRedisClientConfig()
                .redisConnectionFactory(redis, noCustomizers(), new GatewayRedisPoolProperties());

        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void zeroTimeout_alsoFallsBackToTwoSeconds() {
        RedisProperties redis = new RedisProperties();
        redis.setTimeout(Duration.ZERO);
        LettuceConnectionFactory factory = new GatewayRedisClientConfig()
                .sseRelayRedisConnectionFactory(redis, ClientResources.builder().build(), new GatewayRedisPoolProperties());

        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void explicitTimeout_isHonoured() {
        RedisProperties redis = new RedisProperties();
        redis.setTimeout(Duration.ofMillis(750));
        LettuceConnectionFactory factory = new GatewayRedisClientConfig()
                .redisConnectionFactory(redis, noCustomizers(), new GatewayRedisPoolProperties());

        assertThat(factory.getClientConfiguration().getCommandTimeout()).isEqualTo(Duration.ofMillis(750));
    }

    @Test
    void relayTemplate_wrapsDedicatedFactory() {
        RedisProperties redis = new RedisProperties();
        ClientResources resources = ClientResources.builder().build();
        LettuceConnectionFactory relayFactory =
                new GatewayRedisClientConfig().sseRelayRedisConnectionFactory(redis, resources, new GatewayRedisPoolProperties());
        ReactiveStringRedisTemplate template =
                new GatewayRedisClientConfig().sseRelayReactiveStringRedisTemplate(relayFactory);

        assertThat(template).isNotNull();
        resources.shutdown();
    }

    @Test
    void poolProperties_carryIndependentSettings() {
        GatewayRedisPoolProperties props = new GatewayRedisPoolProperties();
        assertThat(props.getShared().getIoThreads()).isEqualTo(4);
        assertThat(props.getShared().getComputationThreads()).isEqualTo(4);
        assertThat(props.getSseRelay().getIoThreads()).isEqualTo(4);
        assertThat(props.getSseRelay().getComputationThreads()).isEqualTo(4);

        props.getShared().setIoThreads(9);
        props.getShared().setComputationThreads(8);
        props.getSseRelay().setIoThreads(2);
        props.getSseRelay().setComputationThreads(3);

        assertThat(props.getShared().getIoThreads()).isEqualTo(9);
        assertThat(props.getShared().getComputationThreads()).isEqualTo(8);
        assertThat(props.getSseRelay().getIoThreads()).isEqualTo(2);
        assertThat(props.getSseRelay().getComputationThreads()).isEqualTo(3);
    }
}
