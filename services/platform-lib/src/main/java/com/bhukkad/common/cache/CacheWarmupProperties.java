package com.bhukkad.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for startup cache warmup.
 *
 * <p>When {@code bhukkad.cache.warmup.enabled=true} (default),
 * {@link CacheWarmupService} listens for {@link org.springframework.boot.context.event.ApplicationReadyEvent}
 * and loads every hot key contributed by a {@link CacheWarmupRegistry} bean into
 * the L1+L2 cache.</p>
 */
@ConfigurationProperties(prefix = "bhukkad.cache.warmup")
public class CacheWarmupProperties {

    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}