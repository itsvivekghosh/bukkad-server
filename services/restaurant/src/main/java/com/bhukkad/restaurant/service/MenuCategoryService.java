package com.bhukkad.restaurant.service;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.MenuCategory;
import com.bhukkad.restaurant.domain.MenuCategoryRepository;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Menu category CRUD (Batch 4 wave 2 migration).
 *
 * <p>Restaurant-service port of the category surface of the monolith
 * {@code com.bhukkad.serviceImpl.MenuServiceImpl}. The service-local variant
 * checks restaurant existence directly (owner authz stays behind the gateway)
 * and drops the Redis cache layer. The monolith keeps its working copy until
 * the gateway flips.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuCategoryService {

    private final MenuCategoryRepository menuCategoryRepository;
    private final MenuItemRepository menuItemRepository;
    private final com.bhukkad.restaurant.domain.RestaurantRepository restaurantRepository;

    @Transactional
    public MenuCategory create(Long restaurantId, MenuCategory category) {
        requireRestaurant(restaurantId);
        category.setRestaurantId(restaurantId);
        if (category.getDisplayOrder() == null) {
            category.setDisplayOrder(0);
        }
        if (category.getActive() == null) {
            category.setActive(true);
        }
        return menuCategoryRepository.save(category);
    }

    public List<MenuCategory> listByRestaurant(Long restaurantId) {
        return menuCategoryRepository.findByRestaurantIdOrderByDisplayOrderAsc(restaurantId);
    }

    @Transactional
    public MenuCategory update(Long categoryId, MenuCategory patch) {
        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (patch.getName() != null) category.setName(patch.getName());
        if (patch.getDescription() != null) category.setDescription(patch.getDescription());
        if (patch.getDisplayOrder() != null) category.setDisplayOrder(patch.getDisplayOrder());
        if (patch.getActive() != null) category.setActive(patch.getActive());
        return menuCategoryRepository.save(category);
    }

    @Transactional
    public void delete(Long categoryId) {
        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        if (menuItemRepository.countByCategoryId(categoryId) > 0) {
            throw new BusinessException("Cannot delete category because it contains menu items");
        }
        menuCategoryRepository.delete(category);
    }

    private void requireRestaurant(Long restaurantId) {
        if (!restaurantRepository.existsById(restaurantId)) {
            throw new ResourceNotFoundException("Restaurant not found");
        }
    }
}
