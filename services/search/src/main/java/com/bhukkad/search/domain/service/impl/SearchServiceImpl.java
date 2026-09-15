package com.bhukkad.search.domain.service.impl;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.search.api.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.api.dto.response.MenuItemSearchResult;
import com.bhukkad.search.api.dto.response.RestaurantSearchResult;
import com.bhukkad.search.api.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.config.SearchFuzzyProperties;
import com.bhukkad.search.domain.entity.MenuItemSearchEntity;
import com.bhukkad.search.domain.entity.RestaurantSearchEntity;
import com.bhukkad.search.domain.repository.MenuItemSearchRepository;
import com.bhukkad.search.domain.repository.RestaurantSearchRepository;
import com.bhukkad.search.domain.service.SearchService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements SearchService {

    /** Unified search page bound (both result groups, per call). */
    static final int UNIFIED_RESULT_LIMIT = 50;

    private final RestaurantSearchRepository restaurantSearchRepository;
    private final MenuItemSearchRepository menuItemSearchRepository;
    private final SearchFuzzyProperties fuzzyProperties;
    private final org.springframework.beans.factory.ObjectProvider<RedisCacheService> cacheProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public SearchServiceImpl(RestaurantSearchRepository restaurantSearchRepository,
                             MenuItemSearchRepository menuItemSearchRepository,
                             SearchFuzzyProperties fuzzyProperties,
                             org.springframework.beans.factory.ObjectProvider<RedisCacheService> cacheProvider) {
        this.restaurantSearchRepository = restaurantSearchRepository;
        this.menuItemSearchRepository = menuItemSearchRepository;
        this.fuzzyProperties = fuzzyProperties;
        this.cacheProvider = cacheProvider;
    }

    // Back-compat constructor for tests without Redis.
    public SearchServiceImpl(RestaurantSearchRepository restaurantSearchRepository,
                             MenuItemSearchRepository menuItemSearchRepository,
                             SearchFuzzyProperties fuzzyProperties) {
        this(restaurantSearchRepository, menuItemSearchRepository, fuzzyProperties, null);
    }

    private RedisCacheService redisCache() {
        if (cacheProvider == null) {
            return null;
        }
        return cacheProvider.getIfAvailable();
    }

    private static final String SEARCH_CACHE_KEY_PREFIX = "search:unified:";
    private static final String SUGGEST_CACHE_KEY_PREFIX = "search:suggest:";
    private static final long SEARCH_CACHE_TTL_SECONDS = 60;
    private static final long SUGGEST_CACHE_TTL_SECONDS = 30;

    @Override
    public UnifiedSearchResponse unifiedSearch(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
        }

        String cacheKey = SEARCH_CACHE_KEY_PREFIX + keyword.trim().toLowerCase(java.util.Locale.ROOT);
        RedisCacheService cache = redisCache();
        if (cache != null) {
            return cache.getOrCompute(cacheKey, UnifiedSearchResponse.class, SEARCH_CACHE_TTL_SECONDS, () -> {
                return executeSearch(keyword);
            });
        }
        return executeSearch(keyword);
    }

    private UnifiedSearchResponse executeSearch(String keyword) {
        var page = org.springframework.data.domain.PageRequest.of(0, UNIFIED_RESULT_LIMIT);
        List<RestaurantSearchResult> restaurantResults;
        List<MenuItemSearchResult> menuItemResults;
        if (fuzzyProperties.isEnabled()) {
            String term = keyword.trim().toLowerCase(java.util.Locale.ROOT);
            double threshold = fuzzyProperties.getSimilarityThreshold();
            restaurantResults = restaurantSearchRepository.searchTextFuzzy(term, threshold, page).stream()
                    .map(this::convertToRestaurantSearchResult)
                    .collect(Collectors.toList());
            menuItemResults = menuItemSearchRepository.searchTextFuzzy(term, threshold, page).stream()
                    .map(this::convertToMenuItemSearchResult)
                    .collect(Collectors.toList());
        } else {
            String searchTerm = like(escapeLike(keyword.trim()));
            restaurantResults =
                    restaurantSearchRepository.searchText(searchTerm, page).stream()
                            .map(this::convertToRestaurantSearchResult)
                            .collect(Collectors.toList());
            menuItemResults =
                    menuItemSearchRepository.searchText(searchTerm, page).stream()
                            .map(this::convertToMenuItemSearchResult)
                            .collect(Collectors.toList());
        }

        return new UnifiedSearchResponse(
                restaurantResults,
                menuItemResults,
                restaurantResults.size(),
                menuItemResults.size()
        );
    }

    @Override
    public List<AutocompleteSuggestion> suggest(String prefix, Integer limit) {
        if (prefix == null || prefix.isEmpty()) {
            return new ArrayList<>();
        }

        String cacheKey = SUGGEST_CACHE_KEY_PREFIX + prefix.trim().toLowerCase(java.util.Locale.ROOT) + ":" + limit;
        RedisCacheService cache = redisCache();
        if (cache != null) {
            return cache.getOrCompute(cacheKey, List.class, SUGGEST_CACHE_TTL_SECONDS, () -> {
                return executeSuggest(prefix, limit);
            });
        }
        return executeSuggest(prefix, limit);
    }

    private List<AutocompleteSuggestion> executeSuggest(String prefix, Integer limit) {
        String searchTerm = escapeLike(prefix.trim().toLowerCase());
        int safeLimit = Math.max(limit, 1);
        List<AutocompleteSuggestion> suggestions = new ArrayList<>();

        for (RestaurantSearchEntity entity : restaurantSearchRepository.searchNamePrefix(
                searchTerm, org.springframework.data.domain.PageRequest.of(0, safeLimit))) {
            suggestions.add(new AutocompleteSuggestion(
                    entity.getName(), AutocompleteSuggestion.TYPE_RESTAURANT));
        }
        int remaining = safeLimit - suggestions.size();
        if (remaining > 0) {
            for (MenuItemSearchEntity entity : menuItemSearchRepository.searchNamePrefix(
                    searchTerm, org.springframework.data.domain.PageRequest.of(0, remaining))) {
                suggestions.add(new AutocompleteSuggestion(
                        entity.getName(), AutocompleteSuggestion.TYPE_MENU_ITEM));
            }
        }

        if (suggestions.size() > limit) {
            suggestions = suggestions.subList(0, limit);
        }

        return suggestions;
    }

    /** Escape LIKE metacharacters (%, _, \\) so a customer cannot alter pattern semantics. */
    public static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").toLowerCase(java.util.Locale.ROOT);
    }
    private static String like(String term) {
        return term; // patterns applied inside the query (CONCAT)
    }



    private RestaurantSearchResult convertToRestaurantSearchResult(RestaurantSearchEntity entity) {
        RestaurantSearchResult result = new RestaurantSearchResult();
        result.setId(entity.getId());
        result.setName(entity.getName());
        result.setDescription(entity.getDescription());
        result.setImageUrl(entity.getImageUrl());
        result.setIsOpen(entity.getIsOpen());
        result.setIsActive(entity.getIsActive());
        result.setAverageRating(entity.getAverageRating());
        result.setTotalReviews(entity.getTotalReviews());
        result.setCuisineSummary(entity.getCuisineSummary());
        result.setDistanceKm(entity.getDistanceKm());
        return result;
    }

    private MenuItemSearchResult convertToMenuItemSearchResult(MenuItemSearchEntity entity) {
        MenuItemSearchResult result = new MenuItemSearchResult();
        result.setId(entity.getId());
        result.setName(entity.getName());
        result.setDescription(entity.getDescription());
        result.setCategoryName(entity.getCategoryName());
        result.setPrice(entity.getPrice());
        result.setOriginalPrice(entity.getOriginalPrice());
        result.setDiscountPercentage(entity.getDiscountPercentage());
        result.setAvailable(entity.getAvailable());
        result.setFoodType(entity.getFoodType());
        result.setIsVeg(entity.getIsVeg());
        result.setImageUrl(entity.getImageUrl());
        result.setPreparationTime(entity.getPreparationTime());
        result.setBestseller(entity.getBestseller());
        result.setRestaurantName(entity.getRestaurantName());
        result.setRestaurantDistanceKm(entity.getRestaurantDistanceKm());
        return result;
    }
}