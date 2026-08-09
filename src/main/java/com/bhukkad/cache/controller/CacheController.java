package com.bhukkad.cache.controller;

import com.bhukkad.cache.RedisCacheService;
import com.bhukkad.dto.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/cache")
@RequiredArgsConstructor
public class CacheController {

    private final RedisCacheService cacheService;

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCacheStats() {
        Map<String, Object> stats = cacheService.getCacheStats();
        return ResponseEntity.ok(ApiResponse.success("Cache statistics", stats));
    }

    @DeleteMapping("/clear")
    public ResponseEntity<ApiResponse<String>> clearAllCaches() {
        cacheService.clearAll();
        return ResponseEntity.ok(ApiResponse.success("All caches cleared", "OK"));
    }

    @DeleteMapping("/clear/{pattern}")
    public ResponseEntity<ApiResponse<String>> clearCacheByPattern(@PathVariable String pattern) {
        cacheService.deletePattern(pattern);
        return ResponseEntity.ok(ApiResponse.success("Cache cleared for pattern: " + pattern, "OK"));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cacheHealth() {
        Map<String, Object> health = new java.util.LinkedHashMap<>();
        try {
            cacheService.set("health-check", "ok", 10);
            var result = cacheService.get("health-check", String.class);
            health.put("status", result.isPresent() ? "UP" : "DOWN");
            health.put("stats", cacheService.getCacheStats());
            cacheService.delete("health-check");
        } catch (Exception e) {
            health.put("status", "DOWN");
            health.put("error", e.getMessage());
        }
        return ResponseEntity.ok(ApiResponse.success(health));
    }
}