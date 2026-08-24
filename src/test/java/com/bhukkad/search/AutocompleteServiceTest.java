package com.bhukkad.search;

import com.bhukkad.dto.response.AutocompleteSuggestion;
import com.bhukkad.featureflag.FeatureFlagService;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for incremental trie updates (P1-9): after a menu item or
 * restaurant is created/renamed, the in-memory autocomplete index must reflect
 * the change without a full rebuild.
 */
@ExtendWith(MockitoExtension.class)
class AutocompleteServiceTest {

    @Mock
    private TrieIndex trieIndex;
    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private MenuItemRepository menuItemRepository;
    @Mock
    private FeatureFlagService featureFlagService;

    private AutocompleteService service;

    private AutocompleteService buildService() {
        service = new AutocompleteService(trieIndex, restaurantRepository,
                menuItemRepository, featureFlagService);
        return service;
    }

    @Test
    void indexMenuItem_addsEntryToTrie() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();

        service.indexMenuItem(42L, "Paneer Tikka");

        org.mockito.Mockito.verify(trieIndex).insert(
                "Paneer Tikka", 42L, AutocompleteSuggestion.TYPE_MENU_ITEM);
    }

    @Test
    void indexRestaurant_addsEntryToTrie() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();

        service.indexRestaurant(7L, "Spice Hub");

        org.mockito.Mockito.verify(trieIndex).insert(
                "Spice Hub", 7L, AutocompleteSuggestion.TYPE_RESTAURANT);
    }

    @Test
    void indexMenuItem_skipsWhenFeatureFlagDisabled() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(false);
        buildService();

        service.indexMenuItem(42L, "Paneer Tikka");

        org.mockito.Mockito.verifyNoInteractions(trieIndex);
    }

    @Test
    void indexMenuItem_skipsBlankName() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();

        service.indexMenuItem(42L, "   ");

        org.mockito.Mockito.verifyNoInteractions(trieIndex);
    }

    @Test
    void suggest_returnsEmptyWhenFlagDisabled() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(false);
        buildService();

        assertThat(service.suggest("pa", null)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(trieIndex);
    }

    @Test
    void suggest_limitsAndDeduplicatesResults() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();
        when(trieIndex.prefixSearch("pa", 32)).thenReturn(List.of(
                new TrieIndex.Entry("paneer", "Paneer", 1L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("paneer", "Paneer", 2L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pasta", "Pasta", 3L, AutocompleteSuggestion.TYPE_MENU_ITEM)
        ));

        List<AutocompleteSuggestion> result = service.suggest("pa", 2);

        // Dedup by text+type and cap at the limit.
        assertThat(result).hasSize(2);
        assertThat(result).extracting(AutocompleteSuggestion::getText)
                .containsExactly("Paneer", "Pasta");
    }

    @Test
    void suggest_usesDefaultLimitWhenNullOrBelowOne() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();
        when(trieIndex.prefixSearch("pa", 32)).thenReturn(List.of(
                new TrieIndex.Entry("paneer", "Paneer", 1L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pasta", "Pasta", 2L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pizza", "Pizza", 3L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("poha", "Poha", 4L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("paratha", "Paratha", 5L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pakora", "Pakora", 6L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pancake", "Pancake", 7L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("papad", "Papad", 8L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("paella", "Paella", 9L, AutocompleteSuggestion.TYPE_MENU_ITEM)
        ));

        assertThat(service.suggest("pa", null)).hasSize(8);
        assertThat(service.suggest("pa", 0)).hasSize(8);
    }

    @Test
    void suggest_capsLimitAtTwenty() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();
        // effectiveLimit = min(100, 20) = 20 -> prefixSearch(prefix, max(80, 32)) = 80
        when(trieIndex.prefixSearch("pa", 80)).thenReturn(List.of());

        assertThat(service.suggest("pa", 100)).isEmpty();
    }

    @Test
    void suggest_breaksWhenLimitReached() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();
        when(trieIndex.prefixSearch("pa", 32)).thenReturn(List.of(
                new TrieIndex.Entry("paneer", "Paneer", 1L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pasta", "Pasta", 2L, AutocompleteSuggestion.TYPE_MENU_ITEM),
                new TrieIndex.Entry("pizza", "Pizza", 3L, AutocompleteSuggestion.TYPE_MENU_ITEM)
        ));

        List<AutocompleteSuggestion> result = service.suggest("pa", 2);

        assertThat(result).hasSize(2);
    }

    @Test
    void indexRestaurant_skipsWhenIdNull() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();

        service.indexRestaurant(null, "Spice Hub");

        org.mockito.Mockito.verifyNoInteractions(trieIndex);
    }

    @Test
    void indexRestaurant_skipsWhenNameNull() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();

        service.indexRestaurant(7L, null);

        org.mockito.Mockito.verifyNoInteractions(trieIndex);
    }

    @Test
    void indexMenuItem_skipsWhenIdNull() {
        when(featureFlagService.isEnabled("autocomplete-enabled")).thenReturn(true);
        buildService();

        service.indexMenuItem(null, "Paneer Tikka");

        org.mockito.Mockito.verifyNoInteractions(trieIndex);
    }

    @Test
    void refresh_buildsIndexFromRestaurantAndMenuItemRows() {
        buildService();
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Spice Hub"},
                new Object[]{2L, "Green Bowl"}
        ));
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(List.<Object[]>of(
                new Object[]{3L, "Paneer Tikka"}
        ));

        service.refresh();

        org.mockito.Mockito.verify(trieIndex).rebuild(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void refresh_withNoRows() {
        buildService();
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(java.util.Collections.emptyList());
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(java.util.Collections.emptyList());

        service.refresh();

        org.mockito.Mockito.verify(trieIndex).rebuild(List.of());
    }

    @Test
    void buildIndexOnStartup_callsRefresh() {
        buildService();
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(java.util.Collections.emptyList());
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(java.util.Collections.emptyList());

        service.buildIndexOnStartup();

        org.mockito.Mockito.verify(trieIndex).rebuild(org.mockito.ArgumentMatchers.anyList());
    }
}
