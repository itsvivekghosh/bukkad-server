package com.bhukkad.search;

import com.bhukkad.dto.response.AutocompleteSuggestion;
import com.bhukkad.featureflag.FeatureFlagProperties;
import com.bhukkad.featureflag.FeatureFlagService;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AutocompleteServiceTest {

    @Mock
    private RestaurantRepository restaurantRepository;
    @Mock
    private MenuItemRepository menuItemRepository;

    private TrieIndex trie;
    private AutocompleteService service;
    private FeatureFlagService featureFlagService;

    @BeforeEach
    void setUp() {
        trie = new TrieIndex();
        FeatureFlagProperties props = new FeatureFlagProperties();
        props.getFlags().put("autocomplete-enabled", true);
        featureFlagService = new FeatureFlagService(props);
        service = new AutocompleteService(trie, restaurantRepository, menuItemRepository, featureFlagService);
    }

    @Test
    void refresh_buildsIndexFromRepositories() {
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Pizza Palace"},
                new Object[]{2L, "Pasta Hut"}
        ));
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(List.<Object[]>of(
                new Object[]{10L, "Paneer Tikka"}
        ));

        service.refresh();

        assertEquals(3, trie.size());
        assertEquals(1, trie.prefixSearch("piz", 5).size());
        assertEquals(1, trie.prefixSearch("pan", 5).size());
    }

    @Test
    void suggest_returnsTypeTaggedSuggestions() {
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Pizza Palace"}
        ));
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(List.<Object[]>of(
                new Object[]{10L, "Pizza Fries"}
        ));
        service.refresh();

        List<AutocompleteSuggestion> suggestions = service.suggest("pizza", null);

        assertEquals(2, suggestions.size());
        // Lexicographic order: "Pizza Fries" < "Pizza Palace"; display name keeps original case.
        assertEquals("Pizza Fries", suggestions.get(0).getText());
        assertEquals(AutocompleteSuggestion.TYPE_MENU_ITEM, suggestions.get(0).getType());
        assertEquals("Pizza Palace", suggestions.get(1).getText());
        assertEquals(AutocompleteSuggestion.TYPE_RESTAURANT, suggestions.get(1).getType());
    }

    @Test
    void suggest_appliesLimit() {
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Alpha"}, new Object[]{2L, "Apple"}, new Object[]{3L, "Apricot"}
        ));
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(List.of());
        service.refresh();

        List<AutocompleteSuggestion> suggestions = service.suggest("ap", 2);

        assertEquals(2, suggestions.size());
    }

    @Test
    void suggest_emptyPrefix_returnsEmpty() {
        assertTrue(service.suggest("", null).isEmpty());
        assertTrue(service.suggest("zzz-no-match", null).isEmpty());
    }

    @Test
    void suggest_disabledFlag_returnsEmpty() {
        when(restaurantRepository.findActiveRestaurantNames()).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Pizza Palace"}
        ));
        when(menuItemRepository.findAvailableMenuItemNames()).thenReturn(List.of());
        service.refresh();
        featureFlagService.setFlag("autocomplete-enabled", false);

        assertTrue(service.suggest("pizza", null).isEmpty());
    }
}
