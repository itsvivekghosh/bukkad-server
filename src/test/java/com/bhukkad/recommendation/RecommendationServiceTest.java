package com.bhukkad.recommendation;

import com.bhukkad.entity.MenuItem;
import com.bhukkad.repository.MenuItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock
    private RecommendationQueryService queryService;
    @Mock
    private MenuItemRepository menuItemRepository;

    private RecommendationService service;

    @BeforeEach
    void setUp() {
        service = new RecommendationService(queryService, menuItemRepository);
    }

    private MenuItem item(long id, String name, String categoryName, boolean available) {
        MenuItem item = new MenuItem();
        item.setId(id);
        item.setName(name);
        item.setAvailable(available);
        com.bhukkad.entity.MenuCategory category = new com.bhukkad.entity.MenuCategory();
        category.setName(categoryName);
        item.setCategory(category);
        return item;
    }

    @Test
    void reorderSuggestions_returnsHydratedFrequentItems() {
        when(queryService.findCustomerItemFrequencies(1L, 10))
                .thenReturn(List.of(new Object[]{5L, 12L}, new Object[]{9L, 4L}));
        MenuItem first = item(5, "Margherita", "Pizza", true);
        MenuItem second = item(9, "Garlic Bread", "Sides", true);
        when(queryService.hydrateAvailableItems(List.of(5L, 9L)))
                .thenReturn(List.of(first, second));

        List<MenuItem> result = service.reorderSuggestions(1L);

        assertEquals(List.of(first, second), result);
    }

    @Test
    void collaborativeSuggestions_excludesAlreadyOrderedItemsAndReturnsEmptyForNewCustomer() {
        // New customer: no history → no collaborative signal.
        when(queryService.findCustomerItemFrequencies(2L, 50)).thenReturn(List.of());
        assertTrue(service.collaborativeSuggestions(2L).isEmpty());

        // Existing customer: co-order candidates exclude their own items.
        when(queryService.findCustomerItemFrequencies(3L, 50)).thenReturn(
                List.<Object[]>of(new Object[]{7L, 3L}));
        when(queryService.findCoOrderedItems(anyList(), anyLong(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new Object[]{8L, 20L}, new Object[]{7L, 15L}));
        MenuItem candidate = item(8, "Chilli Paneer", "Mains", true);
        when(queryService.hydrateAvailableItems(List.of(8L))).thenReturn(List.of(candidate));

        List<MenuItem> result = service.collaborativeSuggestions(3L);

        assertEquals(List.of(candidate), result);
    }

    @Test
    void mealWindow_matchesExpectedBands() {
        assertEquals(RecommendationService.MealWindow.BREAKFAST,
                RecommendationService.MealWindow.currentAt(LocalTime.of(7, 0)));
        assertEquals(RecommendationService.MealWindow.LUNCH,
                RecommendationService.MealWindow.currentAt(LocalTime.of(13, 0)));
        assertEquals(RecommendationService.MealWindow.SNACKS,
                RecommendationService.MealWindow.currentAt(LocalTime.of(17, 30)));
        assertEquals(RecommendationService.MealWindow.DINNER,
                RecommendationService.MealWindow.currentAt(LocalTime.of(21, 0)));
    }
}
