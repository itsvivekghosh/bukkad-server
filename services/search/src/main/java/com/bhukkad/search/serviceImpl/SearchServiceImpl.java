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

        String searchTerm = keyword.toLowerCase().trim();

        // Search restaurants
        List<RestaurantSearchEntity> restaurantEntities = restaurantSearchRepository.findAll();
        List<RestaurantSearchResult> restaurantResults = restaurantEntities.stream()
                .filter(entity -> matchesSearchTerm(entity, searchTerm))
                .map(this::convertToRestaurantSearchResult)
                .collect(Collectors.toList());

        // Search menu items
        List<MenuItemSearchEntity> menuItemEntities = menuItemSearchRepository.findAll();
        List<MenuItemSearchResult> menuItemResults = menuItemEntities.stream()
                .filter(entity -> matchesSearchTerm(entity, searchTerm))
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

        String searchTerm = prefix.toLowerCase().trim();
        List<AutocompleteSuggestion> suggestions = new ArrayList<>();

        // Add restaurant name suggestions
        List<RestaurantSearchEntity> restaurantEntities = restaurantSearchRepository.findAll();
        for (RestaurantSearchEntity entity : restaurantEntities) {
            if (entity.getName() != null && 
                entity.getName().toLowerCase().startsWith(searchTerm)) {
                suggestions.add(new AutocompleteSuggestion(
                        entity.getName(),
                        AutocompleteSuggestion.TYPE_RESTAURANT
                ));
                if (suggestions.size() >= limit) {
                    break;
                }
            }
        }

        // Add menu item name suggestions if we need more
        if (suggestions.size() < limit) {
            List<MenuItemSearchEntity> menuItemEntities = menuItemSearchRepository.findAll();
            for (MenuItemSearchEntity entity : menuItemEntities) {
                if (entity.getName() != null && 
                    entity.getName().toLowerCase().startsWith(searchTerm)) {
                    suggestions.add(new AutocompleteSuggestion(
                            entity.getName(),
                            AutocompleteSuggestion.TYPE_MENU_ITEM
                    ));
                    if (suggestions.size() >= limit) {
                        break;
                    }
                }
            }
        }

        // Limit results
        if (suggestions.size() > limit) {
            suggestions = suggestions.subList(0, limit);
        }

        return suggestions;
    }

    private boolean matchesSearchTerm(RestaurantSearchEntity entity, String searchTerm) {
        return (entity.getName() != null && entity.getName().toLowerCase().contains(searchTerm)) ||
                (entity.getDescription() != null && entity.getDescription().toLowerCase().contains(searchTerm)) ||
                (entity.getCuisineSummary() != null && entity.getCuisineSummary().toLowerCase().contains(searchTerm));
    }

    private boolean matchesSearchTerm(MenuItemSearchEntity entity, String searchTerm) {
        return (entity.getName() != null && entity.getName().toLowerCase().contains(searchTerm)) ||
                (entity.getDescription() != null && entity.getDescription().toLowerCase().contains(searchTerm)) ||
                (entity.getCategoryName() != null && entity.getCategoryName().toLowerCase().contains(searchTerm)) ||
                (entity.getFoodType() != null && entity.getFoodType().toLowerCase().contains(searchTerm));
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