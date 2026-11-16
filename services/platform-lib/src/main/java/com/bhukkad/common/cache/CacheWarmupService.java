package com.bhukkad.common.cache;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms a configurable set of hot keys into the L1+L2 cache on startup.
 *
 * <p>The key set is pluggable: each service contributes keys through a
 * {@link CacheWarmupRegistry} bean, which this service collects. platform-lib
 * never needs to know about any individual service's keys.</p>
 *
 * <p>Safety first: every failure is logged and swallowed so a broken loader
 * or a transient Redis issue can never block application startup.</p>
 */
@Component
public class CacheWarmupService {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmupService.class);

    private final List<CacheWarmupRegistry> registries;
    private final ObjectProvider<RedisCacheService> cacheProvider;
    private final CacheWarmupProperties properties;

    public CacheWarmupService(List<CacheWarmupRegistry> registries,
                              ObjectProvider<RedisCacheService> cacheProvider,
                              CacheWarmupProperties properties) {
        this.registries = registries != null ? registries : List.of();
        this.cacheProvider = cacheProvider;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warm() {
        if (!properties.isEnabled()) {
            log.debug("CACHE_WARMUP_DISABLED");
            return;
        }
        RedisCacheService cache = cacheProvider.getIfAvailable();
        if (cache == null) {
            log.warn("CACHE_WARMUP_SKIP_NO_REDIS_CACHE_SERVICE");
            return;
        }

        int loaded = 0;
        int failed = 0;
        for (CacheWarmupRegistry registry : registries) {
            List<CacheWarmupEntry> entries;
            try {
                entries = registry.warmupEntries();
            } catch (Throwable t) {
                log.warn("CACHE_WARMUP_REGISTRY_FAILED registry={} error={}",
                        registry.getClass().getName(), t.getMessage());
                failed++;
                continue;
            }
            if (entries == null) {
                continue;
            }
            for (CacheWarmupEntry entry : entries) {
                try {
                    cache.getOrCompute(entry.key(), Object.class, entry.ttlSeconds(), entry.loader());
                    loaded++;
                } catch (Throwable t) {
                    log.warn("CACHE_WARMUP_ENTRY_FAILED key={} ttlSeconds={} error={}",
                            entry.key(), entry.ttlSeconds(), t.getMessage());
                    failed++;
                }
            }
        }

        log.info("CACHE_WARMUP_COMPLETE loaded={} failed={}", loaded, failed);
    }
}