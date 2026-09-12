package com.bhukkad.restaurant.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.ResourceNotFoundException;
import com.bhukkad.restaurant.domain.entity.MenuCategory;
import com.bhukkad.restaurant.domain.repository.MenuCategoryRepository;
import com.bhukkad.restaurant.domain.repository.MenuItemRepository;
import com.bhukkad.restaurant.domain.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MenuCategoryServiceTest {

    @Mock
    private MenuCategoryRepository menuCategoryRepository;

    @Mock
    private MenuItemRepository menuItemRepository;

    @Mock
    private RestaurantRepository restaurantRepository;

    @InjectMocks
    private MenuCategoryService service;

    @Test
    void create_requiresExistingRestaurant() {
        when(restaurantRepository.existsById(9L)).thenReturn(false);

        assertThatThrownBy(() -> service.create(9L, new MenuCategory()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_appliesDefaultsAndSaves() {
        when(restaurantRepository.existsById(1L)).thenReturn(true);
        when(menuCategoryRepository.save(any(MenuCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuCategory input = new MenuCategory();
        input.setName("Starters");

        MenuCategory saved = service.create(1L, input);

        ArgumentCaptor<MenuCategory> captor = ArgumentCaptor.forClass(MenuCategory.class);
        verify(menuCategoryRepository).save(captor.capture());
        assertThat(captor.getValue().getRestaurantId()).isEqualTo(1L);
        assertThat(captor.getValue().getDisplayOrder()).isZero();
        assertThat(captor.getValue().getActive()).isTrue();
        assertThat(saved.getName()).isEqualTo("Starters");
    }

    @Test
    void update_modifiesOnlyProvidedFields() {
        MenuCategory existing = new MenuCategory();
        existing.setId(3L);
        existing.setName("Old");
        existing.setActive(true);
        when(menuCategoryRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(menuCategoryRepository.save(any(MenuCategory.class))).thenAnswer(inv -> inv.getArgument(0));

        MenuCategory patch = new MenuCategory();
        patch.setName("New");
        patch.setActive(false);

        MenuCategory updated = service.update(3L, patch);

        assertThat(updated.getName()).isEqualTo("New");
        assertThat(updated.getActive()).isFalse();
    }

    @Test
    void update_throws_whenMissing() {
        when(menuCategoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(99L, new MenuCategory()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_rejectsNonEmptyCategory() {
        MenuCategory existing = new MenuCategory();
        existing.setId(3L);
        when(menuCategoryRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(menuItemRepository.countByCategoryId(3L)).thenReturn(2);

        assertThatThrownBy(() -> service.delete(3L))
                .isInstanceOf(BusinessException.class);
        verify(menuCategoryRepository, never()).delete(any(MenuCategory.class));
    }

    @Test
    void delete_removesEmptyCategory() {
        MenuCategory existing = new MenuCategory();
        existing.setId(3L);
        when(menuCategoryRepository.findById(3L)).thenReturn(Optional.of(existing));
        when(menuItemRepository.countByCategoryId(3L)).thenReturn(0);

        service.delete(3L);

        verify(menuCategoryRepository).delete(existing);
    }

    @Test
    void listByRestaurant_ordersByDisplayOrder() {
        MenuCategory first = new MenuCategory();
        first.setName("Starters");
        MenuCategory second = new MenuCategory();
        second.setName("Mains");
        when(menuCategoryRepository.findByRestaurantIdOrderByDisplayOrderAsc(1L))
                .thenReturn(List.of(first, second));

        assertThat(service.listByRestaurant(1L)).extracting(MenuCategory::getName)
                .containsExactly("Starters", "Mains");
    }
}
