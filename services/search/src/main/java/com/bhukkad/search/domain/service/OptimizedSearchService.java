package com.bhukkad.search.domain.service;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.search.api.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.api.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.domain.service.impl.SearchServiceImpl;
import com.bhukkad.search.infrastructure.client.ElasticsearchSearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Optimized search service with explicit 3-tier cache architecture.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>L1: Caffeine in-JVM cache via {@link RedisCacheService} (short TTL, ~1ms).</li>
 *   <li>L2: Redis distributed cache via {@link RedisCacheService} (medium TTL, ~5ms).</li>
 *   <li>L3: Elasticsearch cluster via {@link ElasticsearchSearchClient} (~20ms).</li>
 *   <li>L4: PostgreSQL full-text search via {@link SearchServiceImpl} (~100ms).</li>
 * </ol>
 */
@Primary
@Service
@RequiredArgsConstructor
@Slf4j
public class OptimizedSearchService implements SearchService {

    private static final String SEARCH_L1_PREFIX = "search:es:l1:";
    private static final String SEARCH_L2_PREFIX = "search:es:l2:";
    private static final long SEARCH_L1_TTL_SECONDS = 60;
    private static final long SEARCH_L2_TTL_SECONDS = 300;

    private final org.springframework.beans.factory.ObjectProvider<RedisCacheService> redisCacheService;
    private final ElasticsearchSearchClient elasticsearchSearchClient;
    private final SearchServiceImpl postgresFallback;

    @Override
    public UnifiedSearchResponse unifiedSearch(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new UnifiedSearchResponse(new java.util.ArrayList<>(), new java.util.ArrayList<>(), 0, 0);
        }

        String cacheKey = keyword.trim().toLowerCase(java.util.Locale.ROOT);

        // 1. L1 Cache (Caffeine via RedisCacheService)
        UnifiedSearchResponse l1 = getFromL1(cacheKey);
        if (l1 != null) {
            return l1;
        }

        // 2. L2 Cache (Redis)
        UnifiedSearchResponse l2 = getFromL2(cacheKey);
        if (l2 != null) {
            // Populate L1 for next request
            putInL1(cacheKey, l2);
            return l2;
        }

        // 3. L3 Elasticsearch
        try {
            UnifiedSearchResponse esResult = elasticsearchSearchClient.search(keyword);
            if (esResult != null && (esResult.getRestaurants() != null || esResult.getMenuItems() != null)) {
                // Cache in L1/L2
                putInL1(cacheKey, esResult);
                putInL2(cacheKey, esResult);
                return esResult;
            }
        } catch (Exception ex) {
            log.warn("SEARCH_ES_FAILED keyword={} error={}", keyword, ex.getMessage());
        }

        // 4. L4 PostgreSQL fallback
        UnifiedSearchResponse pgResult = postgresFallback.unifiedSearch(keyword);
        if (pgResult != null) {
            putInL2(cacheKey, pgResult);
        }
        return pgResult != null ? pgResult : new UnifiedSearchResponse(new java.util.ArrayList<>(), new java.util.ArrayList<>(), 0, 0);
    }

    @Override
    public List<AutocompleteSuggestion> suggest(String prefix, Integer limit) {
        if (prefix == null || prefix.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        int safeLimit = Math.max(1, Math.min(limit, 50));
        String cacheKey = "suggest:" + prefix.trim().toLowerCase(java.util.Locale.ROOT) + ":" + safeLimit;

        // 1. L1 Cache
        List<AutocompleteSuggestion> l1 = getFromL1(cacheKey);
        if (l1 != null) {
            return l1;
        }

        // 2. L2 Cache
        List<AutocompleteSuggestion> l2 = getFromL2(cacheKey);
        if (l2 != null) {
            putInL1(cacheKey, l2);
            return l2;
        }

        // 3. L3 Elasticsearch suggest
        try {
            List<AutocompleteSuggestion> esResult = elasticsearchSearchClient.suggest(prefix, safeLimit);
            if (esResult != null && !esResult.isEmpty()) {
                putInL1(cacheKey, esResult);
                putInL2(cacheKey, esResult);
                return esResult;
            }
        } catch (Exception ex) {
            log.warn("SEARCH_SUGGEST_ES_FAILED prefix={} error={}", prefix, ex.getMessage());
        }

        // 4. L4 PostgreSQL fallback
        List<AutocompleteSuggestion> pgResult = postgresFallback.suggest(prefix, safeLimit);
        if (pgResult != null && !pgResult.isEmpty()) {
            putInL2(cacheKey, pgResult);
        }
        return pgResult != null ? pgResult : new java.util.ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private <T> T getFromL1(String key) {
        RedisCacheService cache = redisCacheService.getIfAvailable();
        if (cache == null) {
            return null;
        }
        try {
            String l1Key = SEARCH_L1_PREFIX + key;
            return (T) cache.get(l1Key, Object.class).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T getFromL2(String key) {
        RedisCacheService cache = redisCacheService.getIfAvailable();
        if (cache == null) {
            return null;
        }
        try {
            String l2Key = SEARCH_L2_PREFIX + key;
            return (T) cache.get(l2Key, Object.class).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void putInL1(String key, Object value) {
        RedisCacheService cache = redisCacheService.getIfAvailable();
        if (cache == null) {
            return;
        }
        try {
            cache.set(SEARCH_L1_PREFIX + key, value, SEARCH_L1_TTL_SECONDS);
        } catch (Exception ex) {
            log.debug("SEARCH_L1_PUT_FAILED key={} error={}", key, ex.getMessage());
        }
    }

    private void putInL2(String key, Object value) {
        RedisCacheService cache = redisCacheService.getIfAvailable();
        if (cache == null) {
            return;
        }
        try {
            cache.set(SEARCH_L2_PREFIX + key, value, SEARCH_L2_TTL_SECONDS);
        } catch (Exception ex) {
            log.debug("SEARCH_L2_PUT_FAILED key={} error={}", key, ex.getMessage());
        }
    }
}
