package com.bhukkad.common.web;

import com.bhukkad.common.cache.LocalCacheService;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops surface for the in-process Caffeine cache (monolith parity):
 * health/stats are public aggregates; clears require ADMIN.
 */
@RestController
public class CacheOpsController {

    private final ObjectProvider<LocalCacheService> cacheProvider;

    public CacheOpsController(ObjectProvider<LocalCacheService> cacheProvider) {
        this.cacheProvider = cacheProvider;
    }

    @GetMapping("/api/v1/cache/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        LocalCacheService cache = cacheProvider.getIfAvailable();
        body.put("status", "UP");
        body.put("provider", "caffeine-local");
        body.put("enabled", cache != null && cache.isEnabled());
        return body;
    }

    @GetMapping("/api/v1/cache/stats")
    public Map<String, Object> stats() {
        LocalCacheService cache = cacheProvider.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        if (cache == null) {
            body.put("enabled", false);
            return body;
        }
        body.put("enabled", cache.isEnabled());
        body.putAll(cache.getStats());
        return body;
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping({"/api/v1/cache/clear", "/api/v1/cache/clear/*"})
    public Map<String, String> clear() {
        LocalCacheService cache = cacheProvider.getIfAvailable();
        if (cache != null) {
            cache.clearAll();
        }
        return Map.of("message", "cache cleared");
    }
}
