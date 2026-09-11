package com.bhukkad.search.serviceImpl;

import com.bhukkad.search.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.dto.response.MenuItemSearchResult;
import com.bhukkad.search.dto.response.RestaurantSearchResult;
import com.bhukkad.search.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.entity.RestaurantSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements com.bhukkad.search.service.SearchService {

    /** Unified search page bound (both result groups, per call). */
    static final int UNIFIED_RESULT_LIMIT = 50;

    private final RestaurantSearchRepository restaurantSearchRepository;
    private final MenuItemSearchRepository menuItemSearchRepository;
    /**
     * ADR-002 fuzzy path (pg_trgm, V11): default FALSE — the bounded
     * LIKE/-prefix queries stay the compatibility surface until the flag has
     * soaked (product rollout pending).
     */
    private final boolean fuzzySearchEnabled;

    public SearchServiceImpl(RestaurantSearchRepository restaurantSearchRepository,
                             MenuItemSearchRepository menuItemSearchRepository,
                             @org.springframework.beans.factory.annotation.Value(
                                     "${app.search.fuzzy.enabled:false}") boolean fuzzySearchEnabled) {
        this.restaurantSearchRepository = restaurantSearchRepository;
        this.menuItemSearchRepository = menuItemSearchRepository;
        this.fuzzySearchEnabled = fuzzySearchEnabled;
    }

    @Override
    public UnifiedSearchResponse unifiedSearch(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
        }

        // The LIKE path uses the escaped, lowercased term (no pattern
        // widening); the fuzzy path wants the plain lowercased term — LIKE
        // escapes would pollute the trigram similarity.
        String likeTerm = escapeLike(keyword.trim());
        String fuzzyTerm = keyword.trim().toLowerCase(java.util.Locale.ROOT);
        var page = org.springframework.data.domain.PageRequest.of(0, UNIFIED_RESULT_LIMIT);

        // Bounded DB-side text search (previously findAll() loaded both
        // projection tables per public request).
        List<RestaurantSearchResult> restaurantResults =
                (fuzzySearchEnabled
                        ? restaurantSearchRepository.searchTextFuzzy(fuzzyTerm, UNIFIED_RESULT_LIMIT)
                        : restaurantSearchRepository.searchText(likeTerm, page))
                .stream()
                        .map(this::convertToRestaurantSearchResult)
                        .collect(Collectors.toList());
        List<MenuItemSearchResult> menuItemResults =
                (fuzzySearchEnabled
                        ? menuItemSearchRepository.searchTextFuzzy(fuzzyTerm, UNIFIED_RESULT_LIMIT)
                        : menuItemSearchRepository.searchText(likeTerm, page))
                .stream()
                        .map(this::convertToMenuItemSearchResult)
                        .collect(Collectors.toList());

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

        String searchTerm = escapeLike(prefix.trim().toLowerCase());
        int safeLimit = Math.max(limit, 1);
        List<AutocompleteSuggestion> suggestions = new ArrayList<>();

        // Prefix queries run in the DB with a hard page size; negative limits
        // (audit S-3) are clamped by the caller anyway — defensive here too.
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

        // Limit results
        if (suggestions.size() > limit) {
            suggestions = suggestions.subList(0, limit);
        }

        return suggestions;
    }

    /** Escape LIKE metacharacters (%, _, \\) so a customer cannot alter pattern semantics. */
    public static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").toLowerCase(java.util.Locale.ROOT);
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