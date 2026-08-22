package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.AutocompleteSuggestion;
import com.bhukkad.dto.response.UnifiedSearchResponse;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.search.AutocompleteService;
import com.bhukkad.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/search")
@RequiredArgsConstructor
@Tag(name = "Search", description = "REST endpoints for Search")
public class SearchController {

    private final SearchService searchService;
    private final AutocompleteService autocompleteService;

    @GetMapping
    @RateLimited("search")
    @Operation(summary = "Unified search", description = "See the controller source for details")
    public ResponseEntity<ApiResponse<UnifiedSearchResponse>> unifiedSearch(@RequestParam String keyword) {
        return ResponseEntity.ok(ApiResponse.success(searchService.unifiedSearch(keyword)));
    }

    @GetMapping("/suggest")
    @RateLimited("search")
    @Operation(summary = "Suggest", description = "See the controller source for details")
    public ResponseEntity<ApiResponse<List<AutocompleteSuggestion>>> suggest(
            @RequestParam String q,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(ApiResponse.success(autocompleteService.suggest(q, limit)));
    }
}
