package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Menu autocomplete backed by the in-memory {@link TrieIndex} (port of monolith
 * {@code AutocompleteService}). Warmed from menu item names at startup.
 */
@Service
@RequiredArgsConstructor
public class AutocompleteService {

    private final MenuItemRepository menuItemRepository;
    private volatile TrieIndex index = new TrieIndex();

    public List<String> suggest(String prefix, int limit) {
        return index().autocomplete(prefix, limit);
    }

    // Warms the trie when the context is ready; Spring annotations keep the
    // service layer free of jakarta.annotation deps (RestaurantServiceArchTest).
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmOnStartup() {
        warm();
    }

    public void warm() {
        TrieIndex fresh = new TrieIndex();
        menuItemRepository.findAll().forEach(mi -> fresh.insert(mi.getName()));
        this.index = fresh;
    }

    private TrieIndex index() {
        TrieIndex current = index;
        if (!current.contains("")) {
            // First access warms; empty-string check is cheap and race-safe
            // enough for the warmup heuristic.
            warm();
        }
        return index;
    }
}