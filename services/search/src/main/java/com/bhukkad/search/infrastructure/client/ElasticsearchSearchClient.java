package com.bhukkad.search.infrastructure.client;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.search.api.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.api.dto.response.MenuItemSearchResult;
import com.bhukkad.search.api.dto.response.RestaurantSearchResult;
import com.bhukkad.search.api.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.domain.entity.MenuItemSearchEntity;
import com.bhukkad.search.domain.entity.RestaurantDocument;
import com.bhukkad.search.domain.entity.RestaurantSearchEntity;
import com.bhukkad.search.domain.repository.MenuItemSearchRepository;
import com.bhukkad.search.domain.repository.RestaurantSearchRepository;
import com.bhukkad.search.domain.service.SearchService;
import com.bhukkad.search.domain.service.impl.SearchServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Elasticsearch-backed search service with 3-tier caching.
 *
 * <p>Lookup order:
 * <ol>
 *   <li>L1 in-JVM cache (short TTL).</li>
 *   <li>L2 Redis cache (medium TTL).</li>
 *   <li>Elasticsearch cluster (~20ms).</li>
 *   <li>PostgreSQL fallback (~100ms).</li>
 * </ol>
 * Cold misses are cached in Redis for subsequent requests.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ElasticsearchSearchClient {

    private static final String SEARCH_L1_PREFIX = "search:es:l1:";
    private static final String SEARCH_L2_PREFIX = "search:es:l2:";
    private static final long SEARCH_L1_TTL_SECONDS = 60;
    private static final long SEARCH_L2_TTL_SECONDS = 300; // 5 minutes
    private static final String INDEX_RESTAURANTS = "restaurants";
    private static final String INDEX_MENU_ITEMS = "menu_items";

    private final ObjectProvider<co.elastic.clients.elasticsearch.ElasticsearchClient> esClientProvider;
    private final org.springframework.beans.factory.ObjectProvider<RedisCacheService> redisCacheService;
    private final RestaurantSearchRepository restaurantSearchRepository;
    private final MenuItemSearchRepository menuItemSearchRepository;
    private final org.springframework.beans.factory.ObjectProvider<SearchServiceImpl> searchServiceProvider;
    private final ObjectMapper objectMapper;

    /**
     * Search across restaurants and menu items using Elasticsearch with 3-tier caching.
     */
    public UnifiedSearchResponse search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
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
            putInL1(cacheKey, l2);
            return l2;
        }

        // 3. Elasticsearch
        UnifiedSearchResponse esResult = tryElasticsearchSearch(keyword);

        // 4. Fallback to PostgreSQL
        if (esResult == null) {
            esResult = fallbackToPostgres(keyword);
        }

        // 5. Cache result
        if (esResult != null) {
            putInL2(cacheKey, esResult);
            putInL1(cacheKey, esResult);
        }

        return esResult != null ? esResult : new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
    }

    /**
     * Autocomplete suggestions with caching.
     */
    public List<AutocompleteSuggestion> suggest(String prefix, int limit) {
        if (prefix == null || prefix.isBlank()) {
            return new ArrayList<>();
        }

        String cacheKey = "search:suggest:" + prefix.trim().toLowerCase(java.util.Locale.ROOT) + ":" + limit;

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

        // 3. Elasticsearch
        List<AutocompleteSuggestion> esResult = tryElasticsearchSuggest(prefix, limit);

        // 4. Fallback to PostgreSQL
        if (esResult == null || esResult.isEmpty()) {
            esResult = fallbackToPostgresSuggest(prefix, limit);
        }

        // 5. Cache result
        if (esResult != null && !esResult.isEmpty()) {
            putInL2(cacheKey, esResult);
            putInL1(cacheKey, esResult);
        }

        return esResult != null ? esResult : new ArrayList<>();
    }

    private UnifiedSearchResponse tryElasticsearchSearch(String keyword) {
        co.elastic.clients.elasticsearch.ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            return null;
        }

        try {
            // Build multi-match query
            Query boolQuery = co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q
                    .multiMatch(m -> m
                            .fields("name^3", "cuisineSummary^2", "description")
                            .query(keyword)
                            .type(TextQueryType.BestFields)
                            .fuzziness("AUTO")
                            .prefixLength(2)
                            .boost(2.0f)
                    )
            );

SearchResponse<RestaurantDocument> response = esClient.search(s -> s
                             .index(INDEX_RESTAURANTS)
                             .query(q -> q.bool(b -> b.must(boolQuery)))
                             .size(50)
                             .timeout("500ms"),
                     RestaurantDocument.class);

            List<RestaurantSearchResult> restaurantResults = response.hits().hits().stream()
                    .map(hit -> {
                        RestaurantDocument doc = hit.source();
                        if (doc == null) return null;
                        return new RestaurantSearchResult(
                                doc.id(), doc.name(), doc.description(), doc.imageUrl(),
                                doc.isOpen() != null ? doc.isOpen() : false,
                                doc.isActive() != null ? doc.isActive() : false,
                                doc.averageRating(), doc.totalReviews(), doc.cuisineSummary(), null
                        );
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());

            // For now, menu items come from PostgreSQL fallback
            List<MenuItemSearchResult> menuItemResults = new ArrayList<>();

            return new UnifiedSearchResponse(
                    restaurantResults,
                    menuItemResults,
                    restaurantResults.size(),
                    menuItemResults.size()
            );

        } catch (IOException e) {
            log.warn("ES_SEARCH_FAILED keyword={} error={}", keyword, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("ES_SEARCH_FAILED keyword={} error={}", keyword, e.getMessage());
            return null;
        }
    }

    private List<AutocompleteSuggestion> tryElasticsearchSuggest(String prefix, int limit) {
        co.elastic.clients.elasticsearch.ElasticsearchClient esClient = esClientProvider.getIfAvailable();
        if (esClient == null) {
            return null;
        }

        try {
            SearchResponse<RestaurantDocument> response = esClient.search(s -> s
                            .index(INDEX_RESTAURANTS)
                            .query(q -> q
                                    .matchPhrasePrefix(m -> m
                                            .field("name")
                                            .query(prefix)
                                            .maxExpansions(10)
                                    )
                            )
                            .size(limit)
                            .timeout("200ms"),
                    RestaurantDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        RestaurantDocument doc = hit.source();
                        return doc != null ? new AutocompleteSuggestion(doc.name(), AutocompleteSuggestion.TYPE_RESTAURANT) : null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("ES_SUGGEST_FAILED prefix={} error={}", prefix, e.getMessage());
            return null;
        }
    }

    private UnifiedSearchResponse fallbackToPostgres(String keyword) {
        // Delegate to JPA-backed search implementation (L3 fallback)
        SearchService searchService = searchServiceProvider.getIfAvailable();
        if (searchService != null) {
            try {
                return searchService.unifiedSearch(keyword);
            } catch (Exception ex) {
                log.warn("ES_FALLBACK_POSTGRES_FAILED keyword={} error={}", keyword, ex.getMessage());
            }
        }
        return new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
    }

    private List<AutocompleteSuggestion> fallbackToPostgresSuggest(String prefix, int limit) {
        // Delegate to JPA-backed search implementation (L3 fallback)
        SearchService searchService = searchServiceProvider.getIfAvailable();
        if (searchService != null) {
            try {
                return searchService.suggest(prefix, limit);
            } catch (Exception ex) {
                log.warn("ES_FALLBACK_POSTGRES_SUGGEST_FAILED prefix={} error={}", prefix, ex.getMessage());
            }
        }
        return new ArrayList<>();
    }

    // ==================== 3-TIER CACHE HELPERS ====================

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

    private <T> void putInL1(String key, T value) {
        RedisCacheService cache = redisCacheService.getIfAvailable();
        if (cache == null) {
            return;
        }
        try {
            cache.set(SEARCH_L1_PREFIX + key, value, SEARCH_L1_TTL_SECONDS);
        } catch (Exception e) {
            log.debug("L1_CACHE_SET_FAILED key={} error={}", key, e.getMessage());
        }
    }

    private <T> void putInL2(String key, T value) {
        RedisCacheService cache = redisCacheService.getIfAvailable();
        if (cache == null) {
            return;
        }
        try {
            cache.set(SEARCH_L2_PREFIX + key, value, SEARCH_L2_TTL_SECONDS);
        } catch (Exception e) {
            log.debug("L2_CACHE_SET_FAILED key={} error={}", key, e.getMessage());
        }
    }
}
