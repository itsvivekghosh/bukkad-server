package com.bhukkad.common.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Boot-resilience contract: a transient Redis failure while starting the
 * cache-invalidation listener must NEVER abort application boot (it used to
 * cancel the whole context refresh), and the starter must keep retrying
 * until the subscription succeeds.
 */
class CacheInvalidationListenerStarterTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<RedisMessageListenerContainer> provider(RedisMessageListenerContainer container) {
        ObjectProvider<RedisMessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(container);
        return provider;
    }

    @Test
    void absentContainer_isCleanNoOp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisMessageListenerContainer> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        CacheInvalidationListenerStarter starter = new CacheInvalidationListenerStarter(provider, 10);

        assertThatCode(starter::startWhenReady).doesNotThrowAnyException();
        assertThat(starter.isStarted()).isFalse();
    }

    @Test
    void startSuccess_marksStarted_withoutRetries() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doNothing().when(container).start();

        CacheInvalidationListenerStarter starter = new CacheInvalidationListenerStarter(provider(container), 10);
        starter.startWhenReady();

        assertThat(starter.isStarted()).isTrue();
        starter.shutdown();
    }

    @Test
    void persistentFailure_neverThrows_andStaysUnstarted() {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
                .when(container).start();

        CacheInvalidationListenerStarter starter = new CacheInvalidationListenerStarter(provider(container), 10_000);

        // Boot path: the failure must be swallowed, not propagated, and the
        // 10s retry delay means the container stays unstarted for the assert.
        assertThatCode(starter::startWhenReady).doesNotThrowAnyException();
        assertThat(starter.isStarted()).isFalse();
        starter.shutdown();
    }

    @Test
    void recoversOnRetry_afterTransientFailure() throws Exception {
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        // First attempt (boot) fails; a scheduled retry succeeds.
        doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
                .doNothing()
                .when(container).start();

        CacheInvalidationListenerStarter starter = new CacheInvalidationListenerStarter(provider(container), 20);

        // Boot path must not propagate the failure.
        assertThatCode(starter::startWhenReady).doesNotThrowAnyException();

        // Retry path: the daemon retry eventually starts the container.
        long deadline = System.currentTimeMillis() + 5_000;
        while (!starter.isStarted() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(starter.isStarted()).as("retry should start the container").isTrue();
        starter.shutdown();
    }
}
