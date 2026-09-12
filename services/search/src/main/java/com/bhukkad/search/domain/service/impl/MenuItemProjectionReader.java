package com.bhukkad.search.domain.service.impl;

import com.bhukkad.search.domain.repository.MenuItemSearchRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-side helpers of the {@code menu_item_search} projection used by the
 * reconciliation sweep. Kept separate from the write service so the sweep
 * reads (count/ids) and writes (upserts/deletes) stay visually distinct.
 */
@Component
public class MenuItemProjectionReader {

    private final MenuItemSearchRepository menuItemSearchRepository;

    public MenuItemProjectionReader(MenuItemSearchRepository menuItemSearchRepository) {
        this.menuItemSearchRepository = menuItemSearchRepository;
    }

    @Transactional(readOnly = true)
    public Integer countById(Long id) {
        return menuItemSearchRepository.countById(id);
    }

    @Transactional(readOnly = true)
    public List<Long> idsForRestaurant(Long restaurantId) {
        return menuItemSearchRepository.findIdsByRestaurantId(restaurantId);
    }
}
