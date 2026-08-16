package com.bhukkad.service;

import com.bhukkad.dto.response.UnifiedSearchResponse;

public interface SearchService {
    UnifiedSearchResponse unifiedSearch(String keyword);
}
