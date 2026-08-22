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
}
