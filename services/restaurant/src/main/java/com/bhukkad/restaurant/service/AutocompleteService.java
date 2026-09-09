package com.bhukkad.restaurant.service;

import com.bhukkad.restaurant.domain.MenuItem;
import com.bhukkad.restaurant.domain.MenuItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Menu autocomplete backed by the in-memory {@link TrieIndex} (port of monolith
 * {@code AutocompleteService}). Warmed from menu item names at startup.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutocompleteService {

    private final MenuItemRepository menuItemRepository;
    private volatile TrieIndex index = new TrieIndex();
    /**
     * The old lazy-warm heuristic used {@code contains("")} which a
     * {@link TrieIndex} can never satisfy (blank words are rejected by
     * insert), so every suggest() call re-ran {@code findAll()} — the
     * opposite of cheap. Once a build succeeds, the startup hook plus
     * {@link #refreshPeriodically()} keep the index fresh.
     */
    private final java.util.concurrent.atomic.AtomicBoolean warmed =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    /**
     * PERF-3: the startup-only build went stale after every menu mutation.
     * The index is POD-LOCAL (in-memory trie), so every replica must refresh
     * its own copy — this ticker deliberately runs without distributed
     * locking (a lock would leave the other replicas stale). Rebuilds into a
     * fresh TrieIndex and swaps it atomically; readers never see a partial
     * index. Default 15 min; failures keep serving the previous index.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.search.autocomplete-refresh-ms:900000}",
            initialDelayString = "${app.search.autocomplete-refresh-ms:900000}")
    public void refreshPeriodically() {
        try {
            warm();
            log.debug("AUTOCOMPLETE_INDEX_REFRESHED");
        } catch (RuntimeException ex) {
            log.warn("AUTOCOMPLETE_INDEX_REFRESH_FAILED keeping previous index: {}", ex.getMessage());
        }
    }

    public void warm() {
        TrieIndex fresh = new TrieIndex();
        menuItemRepository.findAll().forEach(mi -> fresh.insert(mi.getName()));
        this.index = fresh;
        warmed.set(true);
    }

    private TrieIndex index() {
        if (!warmed.get()) {
            // One-time safety net for contexts that miss the ready-event hook;
            // afterwards readers never hit the database.
            warm();
        }
        return index;
    }
}