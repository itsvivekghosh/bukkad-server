package com.bhukkad.search.domain.service;

import com.bhukkad.search.api.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.api.dto.response.UnifiedSearchResponse;

import java.util.List;

/**
 * Search read surface. ADR-002: population is event-driven via
 * {@code SearchSyncEventConsumer} + the reconciliation sweep; the old push
 * contract ({@code indexMenuItem}) was dead — grep-verified zero callers —
 * and was removed.
 */
public interface SearchService {

    UnifiedSearchResponse unifiedSearch(String keyword);

    List<AutocompleteSuggestion> suggest(String prefix, Integer limit);
}
