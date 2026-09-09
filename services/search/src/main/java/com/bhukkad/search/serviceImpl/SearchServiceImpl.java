package com.bhukkad.search.serviceImpl;

import com.bhukkad.search.dto.request.MenuItemIndexRequest;
import com.bhukkad.search.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.dto.response.MenuItemSearchResult;
import com.bhukkad.search.dto.response.RestaurantSearchResult;
import com.bhukkad.search.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.entity.RestaurantSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements com.bhukkad.search.service.SearchService {

    /** Unified search page bound (both result groups, per call). */
    static final int UNIFIED_RESULT_LIMIT = 50;

    private final RestaurantSearchRepository restaurantSearchRepository;
    private final MenuItemSearchRepository menuItemSearchRepository;

    public SearchServiceImpl(RestaurantSearchRepository restaurantSearchRepository,
                             MenuItemSearchRepository menuItemSearchRepository) {
        this.restaurantSearchRepository = restaurantSearchRepository;
        this.menuItemSearchRepository = menuItemSearchRepository;
    }

    /**
     * Upserts a menu-item search document, fed by the monolith on menu-item
     * create/update. Preserves enrichment fields (e.g. restaurantName) that
     * this lightweight payload does not carry.
     */
    @Override
    @Transactional
    public void indexMenuItem(MenuItemIndexRequest request) {
        if (request == null || request.id() == null) {
            return;
        }
        MenuItemSearchEntity entity = menuItemSearchRepository.findById(request.id())
                .orElseGet(() -> {
                    MenuItemSearchEntity fresh = new MenuItemSearchEntity();
                    fresh.setId(request.id());
                    return fresh;
                });
        entity.setName(request.name());
        if (request.description() != null) {
            entity.setDescription(request.description());
        }
        if (request.categoryName() != null) {
            entity.setCategoryName(request.categoryName());
        }
        if (request.price() != null) {
            entity.setPrice(request.price());
        }
        if (request.originalPrice() != null) {
            entity.setOriginalPrice(request.originalPrice());
        }
        if (request.discountPercentage() != null) {
            entity.setDiscountPercentage(request.discountPercentage());
        }
        if (request.available() != null) {
            entity.setAvailable(request.available());
        }
        if (request.foodType() != null) {
            entity.setFoodType(request.foodType());
        }
        if (request.isVeg() != null) {
            entity.setIsVeg(request.isVeg());
        }
        if (request.imageUrl() != null) {
            entity.setImageUrl(request.imageUrl());
        }
        if (request.preparationTime() != null) {
            entity.setPreparationTime(request.preparationTime());
        }
        if (request.bestseller() != null) {
            entity.setBestseller(request.bestseller());
        }
        if (request.restaurantName() != null) {
            entity.setRestaurantName(request.restaurantName());
        }
        menuItemSearchRepository.save(entity);
    }

    @Override
    public UnifiedSearchResponse unifiedSearch(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return new UnifiedSearchResponse(new ArrayList<>(), new ArrayList<>(), 0, 0);
        }

        String searchTerm = like(escapeLike(keyword.trim()));
        var page = org.springframework.data.domain.PageRequest.of(0, UNIFIED_RESULT_LIMIT);

        // Bounded DB-side text search (previously findAll() loaded both
        // projection tables per public request).
        List<RestaurantSearchResult> restaurantResults =
                restaurantSearchRepository.searchText(searchTerm, page).stream()
                        .map(this::convertToRestaurantSearchResult)
                        .collect(Collectors.toList());
        List<MenuItemSearchResult> menuItemResults =
                menuItemSearchRepository.searchText(searchTerm, page).stream()
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