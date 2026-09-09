package com.bhukkad.search.service;

import com.bhukkad.search.dto.request.MenuItemIndexRequest;
import com.bhukkad.search.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.dto.response.UnifiedSearchResponse;

import java.util.List;

public interface SearchService {

    UnifiedSearchResponse unifiedSearch(String keyword);

    List<AutocompleteSuggestion> suggest(String prefix, Integer limit);

    /**
     * Upserts a menu-item search document (fed by the monolith on menu-item
     * create/update). {@code id} and {@code name} are required.
     */
    void indexMenuItem(MenuItemIndexRequest request);
}
