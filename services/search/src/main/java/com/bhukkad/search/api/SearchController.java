package com.bhukkad.search.api;

import com.bhukkad.search.dto.request.MenuItemIndexRequest;
import com.bhukkad.search.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            @RequestParam(required = false, defaultValue = "8") Integer limit) {
        return ResponseEntity.ok(searchService.suggest(q, limit));
    }

    /**
     * Internal index upsert (authenticated): the monolith calls this whenever a
     * menu item is created or updated so the search index stays current.
     */
    @PostMapping("/internal/menu-items")
    @Operation(summary = "Upsert a menu-item search document", description = "Service-to-service index feed")
    public ResponseEntity<Void> indexMenuItem(@Valid @RequestBody MenuItemIndexRequest request) {
        searchService.indexMenuItem(request);
        return ResponseEntity.accepted().build();
    }
}
