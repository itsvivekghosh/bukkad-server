package com.bhukkad.admin.analytics.infrastructure.cache;

import com.bhukkad.admin.analytics.domain.service.FeatureFlagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Starts the admin feature-flag pub/sub listener AFTER the context is fully
 * refreshed, retrying until Redis is reachable instead of failing boot.
 *
 * <p>Why: {@link FeatureFlagRedisConfig#featureFlagListenerContainer} is a
 * {@code SmartLifecycle} whose {@code start()} subscribes synchronously. When
 * Redis is transiently unreachable during a boot storm (or absent, as in CI),
 * that subscription failure cancelled the whole context refresh — the admin
 * service died with "Failed to start bean 'featureFlagListenerContainer'" and
 * its whole test suite errored. Feature-flag hot-reload is best-effort (the
 * service keeps its baked-in defaults until the subscription succeeds), so
 * boot must never depend on it. This mirrors the platform's
 * {@code CacheInvalidationListenerStarter}.</p>
 */
@Component
public class FeatureFlagListenerStarter {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagListenerStarter.class);

    private final ObjectProvider<RedisMessageListenerContainer> containerProvider;
    private final long retryDelayMillis;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ScheduledExecutorService retryExecutor;

    @Autowired
    public FeatureFlagListenerStarter(
            @Qualifier("featureFlagListenerContainer") ObjectProvider<RedisMessageListenerContainer> containerProvider) {
        this(containerProvider, 10_000L);
    }

    FeatureFlagListenerStarter(ObjectProvider<RedisMessageListenerContainer> containerProvider,
                               long retryDelayMillis) {
        this.containerProvider = containerProvider;
        this.retryDelayMillis = retryDelayMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWhenReady() {
        RedisMessageListenerContainer container = containerProvider.getIfAvailable();
        if (container == null) {
            log.debug("FEATURE_FLAG_LISTENER_ABSENT no container bean — nothing to start");
            return;
        }
        if (tryStart(container)) {
            return;
        }
        scheduleRetries(container);
    }

    private void scheduleRetries(RedisMessageListenerContainer container) {
        retryExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "feature-flag-listener-retry");
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
            log.info("FEATURE_FLAG_LISTENER_STARTED subscription active on {}",
                    FeatureFlagService.CHANGED_CHANNEL);
            return true;
        } catch (Exception ex) {
            log.warn("FEATURE_FLAG_LISTENER_START_FAILED reason={} retryInMs={} — flag overrides stay baked-in until Redis is reachable",
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
