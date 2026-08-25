package com.bhukkad.recommendation;

import com.bhukkad.entity.MenuCategory;
import com.bhukkad.entity.MenuItem;
import com.bhukkad.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private RecommendationQueryService queryService;
    @Mock private MenuItemRepository menuItemRepository;

    private RecommendationService service;

    private MenuItem item(String name, String... tags) {
        MenuItem item = new MenuItem();
        item.setName(name);
        item.setTags(java.util.Set.of(tags));
        MenuCategory category = new MenuCategory();
        category.setName("Main Course");
        item.setCategory(category);
        return item;
    }

    @BeforeEach
    void setUp() {
        service = new RecommendationService(queryService, menuItemRepository);
    }

    @Test
    void reorderSuggestions_mapsFrequenciesAndHydrates() {
        when(queryService.findCustomerItemFrequencies(5L, 10))
                .thenReturn(List.<Object[]>of(new Object[]{100L, 3L}, new Object[]{200L, 1L}));
        when(queryService.hydrateAvailableItems(List.of(100L, 200L)))
                .thenReturn(List.of(item("Paneer"), item("Rice")));

        List<MenuItem> result = service.reorderSuggestions(5L);

        assertEquals(2, result.size());
        assertEquals("Paneer", result.get(0).getName());
    }

    @Test
    void collaborativeSuggestions_emptyHistory_returnsEmpty() {
        when(queryService.findCustomerItemFrequencies(5L, 50)).thenReturn(List.<Object[]>of());

        assertTrue(service.collaborativeSuggestions(5L).isEmpty());
    }

    @Test
    void collaborativeSuggestions_excludesOwnAndDedupes() {
        when(queryService.findCustomerItemFrequencies(5L, 50))
                .thenReturn(List.<Object[]>of(new Object[]{100L, 2L}));
        when(queryService.findCoOrderedItems(List.of(100L), 5L, 30))
                .thenReturn(List.<Object[]>of(new Object[]{200L}, new Object[]{300L}, new Object[]{200L}));
        when(queryService.hydrateAvailableItems(List.of(200L, 300L)))
                .thenReturn(List.of(item("Biryani"), item("Samosa")));

        List<MenuItem> result = service.collaborativeSuggestions(5L);

        assertEquals(2, result.size());
    }

    @Test
    void timeAwareSuggestions_matchingReorderHistory() {
        when(queryService.findCustomerItemFrequencies(5L, 10)).thenReturn(List.<Object[]>of(new Object[]{100L, 1L}));
        // Name carries a term from every meal window (breakfast+lunch+snacks+dinner),
        // so the assertion holds regardless of the current time of day.
        when(queryService.hydrateAvailableItems(List.of(100L))).thenReturn(List.of(item("Idli Biryani Samosa Curry")));

        List<MenuItem> result = service.timeAwareSuggestions(5L);

        assertFalse(result.isEmpty());
        // bestsellers fallback must not be queried when personal history matches
        verify(menuItemRepository, never()).findBestsellersByRestaurant(anyLong());
    }

    @Test
    void timeAwareSuggestions_fallsBackToBestsellers() {
        MenuItem anyWindow = item("Idli Biryani Samosa Curry");
        when(queryService.findCustomerItemFrequencies(5L, 10)).thenReturn(List.<Object[]>of(new Object[]{100L, 1L}));
        when(queryService.hydrateAvailableItems(List.of(100L))).thenReturn(List.of(item("Burger")));
        when(queryService.findCustomerRestaurantAffinities(5L, 3)).thenReturn(List.<Object[]>of(new Object[]{1L, 5L}));
        when(menuItemRepository.findBestsellersByRestaurant(1L)).thenReturn(List.of(anyWindow, item("Burger")));

        List<MenuItem> result = service.timeAwareSuggestions(5L);

        // The any-window item matches and is returned from the bestseller fallback
        assertEquals(1, result.size());
        assertEquals("Idli Biryani Samosa Curry", result.get(0).getName());
    }

    @Test
    void rankRestaurantsForCustomer_sortsByAffinityAndKeepsStableOrder() {
        when(queryService.findCustomerRestaurantAffinities(5L, 25))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 9L}, new Object[]{20L, 3L}));

        List<Long> result = service.rankRestaurantsForCustomer(5L, List.of(10L, 20L, 30L));

        // 10 (score 9) first, then 20 (score 3), then unknown 30 keeps position
        assertEquals(List.of(10L, 20L, 30L), result);
    }

    @Test
    void rankRestaurantsForCustomer_stableWithinEqualAffinity() {
        when(queryService.findCustomerRestaurantAffinities(5L, 25))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 5L}, new Object[]{20L, 5L}));

        List<Long> result = service.rankRestaurantsForCustomer(5L, List.of(20L, 10L));

        // equal affinity -> original order preserved
        assertEquals(List.of(20L, 10L), result);
    }

    @Test
    void mealWindow_currentAt_returnsExpectedWindows() {
        assertEquals(RecommendationService.MealWindow.BREAKFAST, RecommendationService.MealWindow.currentAt(LocalTime.of(5, 0)));
        assertEquals(RecommendationService.MealWindow.BREAKFAST, RecommendationService.MealWindow.currentAt(LocalTime.of(10, 59)));
        assertEquals(RecommendationService.MealWindow.LUNCH, RecommendationService.MealWindow.currentAt(LocalTime.of(11, 0)));
        assertEquals(RecommendationService.MealWindow.LUNCH, RecommendationService.MealWindow.currentAt(LocalTime.of(15, 59)));
        assertEquals(RecommendationService.MealWindow.SNACKS, RecommendationService.MealWindow.currentAt(LocalTime.of(16, 0)));
        assertEquals(RecommendationService.MealWindow.SNACKS, RecommendationService.MealWindow.currentAt(LocalTime.of(18, 59)));
        assertEquals(RecommendationService.MealWindow.DINNER, RecommendationService.MealWindow.currentAt(LocalTime.of(19, 0)));
        assertEquals(RecommendationService.MealWindow.DINNER, RecommendationService.MealWindow.currentAt(LocalTime.of(23, 59)));
    }

    @Test
    void matchesWindow_nullNameAndTags_returnsFalse() {
        MenuItem bare = new MenuItem();
        // empty searchable text -> no window match
        when(queryService.findCustomerItemFrequencies(5L, 10)).thenReturn(List.<Object[]>of(new Object[]{100L, 1L}));
        when(queryService.hydrateAvailableItems(List.of(100L))).thenReturn(List.of(bare));
        when(queryService.findCustomerRestaurantAffinities(5L, 3)).thenReturn(List.<Object[]>of());

        List<MenuItem> result = service.timeAwareSuggestions(5L);

        assertTrue(result.isEmpty());
    }
}