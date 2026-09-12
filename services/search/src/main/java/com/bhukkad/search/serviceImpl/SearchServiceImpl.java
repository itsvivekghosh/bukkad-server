package com.bhukkad.search.serviceImpl;

import com.bhukkad.search.config.SearchFuzzyProperties;
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
    private final SearchFuzzyProperties fuzzyProperties;

    public SearchServiceImpl(RestaurantSearchRepository restaurantSearchRepository,
                             MenuItemSearchRepository menuItemSearchRepository,
                             SearchFuzzyProperties fuzzyProperties) {
        this.restaurantSearchRepository = restaurantSearchRepository;
        this.menuItemSearchRepository = menuItemSearchRepository;
        this.fuzzyProperties = fuzzyProperties;
    }

    @Override
    public UnifiedSearchResponse unifiedSearch(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
        }

        var page = org.springframework.data.domain.PageRequest.of(0, UNIFIED_RESULT_LIMIT);
        List<RestaurantSearchResult> restaurantResults;
        List<MenuItemSearchResult> menuItemResults;
        if (fuzzyProperties.isEnabled()) {
            // P-08 OPTION (default off): similarity()-ranked trigram search.
            // Raw lowercased term — trigram matching has no LIKE pattern.
            String term = keyword.trim().toLowerCase(java.util.Locale.ROOT);
            double threshold = fuzzyProperties.getSimilarityThreshold();
            restaurantResults = restaurantSearchRepository.searchTextFuzzy(term, threshold, page).stream()
                    .map(this::convertToRestaurantSearchResult)
                    .collect(Collectors.toList());
            menuItemResults = menuItemSearchRepository.searchTextFuzzy(term, threshold, page).stream()
                    .map(this::convertToMenuItemSearchResult)
                    .collect(Collectors.toList());
        } else {
            // Default (and previously the only) path: bounded, escaped LIKE.
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