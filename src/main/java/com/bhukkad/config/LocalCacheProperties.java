package com.bhukkad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.cache.local")
public class LocalCacheProperties {
    private boolean enabled = true;
    private long maxSize = 5000;
    private long ttlSeconds = 60;
}
