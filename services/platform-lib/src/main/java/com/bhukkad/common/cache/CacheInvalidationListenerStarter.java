package com.bhukkad.common.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Starts the {@link RedisMessageListenerContainer} backing pub/sub cache
 * invalidation AFTER the application context is fully refreshed, retrying
 * until Redis is reachable instead of failing boot.
 *
 * <p>Why: the container is a {@code SmartLifecycle} whose {@code start()}
 * subscribes synchronously. When Redis is transiently unreachable during a
 * fleet-wide boot storm (many JVMs racing Redis at once), that subscription
 * failure cancelled the whole context refresh and killed the service
 * (observed on the deploy-branch E2E boot: identity + realtime 503s).
 * Cache invalidation is best-effort — local caches simply miss until the
 * subscription succeeds — so boot must never depend on it.</p>
 *
 * <p>No container bean (service runs without Redis) is a clean no-op.</p>
 */
@Slf4j
@Component
public class CacheInvalidationListenerStarter {

    private final ObjectProvider<RedisMessageListenerContainer> containerProvider;
    private final long retryDelayMillis;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ScheduledExecutorService retryExecutor;

    // Qualifier required: services may define OTHER RedisMessageListenerContainer
    // beans (e.g. admin-analytics featureFlagListenerContainer) — without it,
    // getIfAvailable() throws NoUniqueBeanDefinitionException at boot.
    @Autowired
    public CacheInvalidationListenerStarter(
            @Qualifier("localCacheInvalidationListenerContainer") ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this(containerProvider, 10_000L);
    }

    CacheInvalidationListenerStarter(ObjectProvider<RedisMessageListenerContainer> containerProvider,
                                     long retryDelayMillis) {
        this.containerProvider = containerProvider;
        this.retryDelayMillis = retryDelayMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWhenReady() {
        RedisMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container == null) {
            log.debug("CACHE_INVALIDATION_LISTENER_ABSENT no container bean — nothing to start");
            return;
        }
        if (tryStart(container)) {
            return;
        }
        scheduleRetries(container);
    }

    private void scheduleRetries(RedisMessageListenerContainer container) {
        retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cache-invalidation-listener-retry");
            thread.setDaemon(true);
            return thread;
        });
        retryExecutor.scheduleWithFixedDelay(() -> {
            if (tryStart(container)) {
                shutdownRetryExecutor();
            }
        }, retryDelayMillis, retryDelayMillis, TimeUnit.MILLISECONDS);
    }

    private boolean tryStart(RedisMessageListenerContainer container) {
        if (started.get()) {
            return true;
        }
        try {
            container.start();
            started.set(true);
            log.info("CACHE_INVALIDATION_LISTENER_STARTED subscription active");
            return true;
        } catch (Exception ex) {
            log.warn("CACHE_INVALIDATION_LISTENER_START_FAILED reason={} retryInMs={} — local caches run unsubscribed until Redis is reachable",
                    ex.getMessage(), retryDelayMillis);
            return false;
        }
    }

    private synchronized void shutdownRetryExecutor() {
        if (retryExecutor != null) {
            retryExecutor.shutdown();
            retryExecutor = null;
        }
    }

    boolean isStarted() {
        return started.get();
    }

    @PreDestroy
    public void shutdown() {
        shutdownRetryExecutor();
    }
}
