package com.bhukkad.search.api;

import com.bhukkad.search.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "REST endpoints for Search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "Unified search", description = "Search across restaurants and menu items")
    public ResponseEntity<UnifiedSearchResponse> unifiedSearch(@RequestParam String keyword) {
        return ResponseEntity.ok(searchService.unifiedSearch(keyword));
    }

    @GetMapping("/suggest")
    @Operation(summary = "Autocomplete suggestions", description = "Return typeahead suggestions for the given prefix")
    public ResponseEntity<List<AutocompleteSuggestion>> suggest(
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "8")
            @Min(value = 1, message = "limit must be at least 1")
            @Max(value = 50, message = "limit must be at most 50") Integer limit) {
        // limit is clamped: a negative value previously hit
        // subList(0, negative) → IllegalArgumentException → 500.
        int safeLimit = Math.min(Math.max(limit == null ? 8 : limit, 1), 50);
        return ResponseEntity.ok(searchService.suggest(q, safeLimit));
    }

    // ADR-002: the old POST /api/v1/search/internal/menu-items push endpoint
    // was removed with W2-ORDER-SEARCH. It had zero callers (grep-verified)
    // and no client ever fed it; population now flows from the restaurant
    // domain events (SearchSyncEventConsumer) plus the periodic
    // reconciliation sweep. Delete propagation is event-driven too
    // (menu_item_deleted).
}
