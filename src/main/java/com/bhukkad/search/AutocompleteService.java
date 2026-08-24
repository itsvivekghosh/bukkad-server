package com.bhukkad.search;

import com.bhukkad.dto.response.AutocompleteSuggestion;
import com.bhukkad.featureflag.FeatureFlagService;
import com.bhukkad.repository.MenuItemRepository;
import com.bhukkad.repository.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Typeahead service over restaurant and menu-item names backed by an
 * in-memory trie. The index is rebuilt on startup and can be refreshed
 * on demand (e.g. via an admin endpoint or a periodic job).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutocompleteService {

    static final int DEFAULT_LIMIT = 8;

    static final String FLAG_AUTOCOMPLETE = "autocomplete-enabled";

    private final TrieIndex trieIndex;
    private final RestaurantRepository restaurantRepository;
    private final MenuItemRepository menuItemRepository;
    private final FeatureFlagService featureFlagService;

    @EventListener(ApplicationReadyEvent.class)
    public void buildIndexOnStartup() {
        refresh();
    }

    /** Rebuilds the trie from the current restaurant and menu-item names. */
    public void refresh() {
        List<TrieIndex.Entry> entries = new ArrayList<>();
        restaurantRepository.findActiveRestaurantNames().forEach(row -> {
            // row: [id, name]
            Object[] values = (Object[]) row;
            entries.add(new TrieIndex.Entry(
                    String.valueOf(values[1]).toLowerCase(),
                    String.valueOf(values[1]),
                    ((Number) values[0]).longValue(),
                    AutocompleteSuggestion.TYPE_RESTAURANT));
        });
        menuItemRepository.findAvailableMenuItemNames().forEach(row -> {
            Object[] values = (Object[]) row;
            entries.add(new TrieIndex.Entry(
                    String.valueOf(values[1]).toLowerCase(),
                    String.valueOf(values[1]),
                    ((Number) values[0]).longValue(),
                    AutocompleteSuggestion.TYPE_MENU_ITEM));
        });
        trieIndex.rebuild(entries);
        log.info("AUTOCOMPLETE_INDEX_REBUILT | entries={}", trieIndex.size());
    }

    /**
     * Incrementally indexes a restaurant by id + name so typeahead reflects new
     * or renamed restaurants without a full rebuild. Called from the restaurant
     * write path; the feature flag is respected so the index only grows when the
     * autocomplete feature is active.
     */
    public void indexRestaurant(Long id, String name) {
        if (!featureFlagService.isEnabled(FLAG_AUTOCOMPLETE) || id == null || name == null || name.isBlank()) {
            return;
        }
        trieIndex.insert(name, id, AutocompleteSuggestion.TYPE_RESTAURANT);
        log.debug("AUTOCOMPLETE_INDEX_ADD | type=restaurant | id={} | name={}", id, name);
    }

    /**
     * Incrementally indexes a menu item by id + name. Same contract as
     * {@link #indexRestaurant(Long, String)} for the menu-item write path.
     */
    public void indexMenuItem(Long id, String name) {
        if (!featureFlagService.isEnabled(FLAG_AUTOCOMPLETE) || id == null || name == null || name.isBlank()) {
            return;
        }
        trieIndex.insert(name, id, AutocompleteSuggestion.TYPE_MENU_ITEM);
        log.debug("AUTOCOMPLETE_INDEX_ADD | type=menuItem | id={} | name={}", id, name);
    }

    /**
     * Returns typeahead suggestions for the given prefix, restaurants first
     * then menu items, limited to {@code limit} results. No-op unless the
     * autocomplete feature flag is enabled (gradual rollout).
     */
    public List<AutocompleteSuggestion> suggest(String prefix, Integer limit) {
        if (!featureFlagService.isEnabled(FLAG_AUTOCOMPLETE)) {
            return List.of();
        }
        int effectiveLimit = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, 20);
        // Deduplicate by text+type (the same dish name can exist across many
        // restaurants) and cap the result at the effective limit.
        java.util.LinkedHashSet<AutocompleteSuggestion> unique = new java.util.LinkedHashSet<>();
        for (TrieIndex.Entry entry : trieIndex.prefixSearch(prefix, Math.max(effectiveLimit * 4, 32))) {
            unique.add(AutocompleteSuggestion.builder()
                    .text(entry.displayName())
                    .type(entry.type())
                    .build());
            if (unique.size() >= effectiveLimit) {
                break;
            }
        }
        return List.copyOf(unique);
    }
}
