package com.bhukkad.controller;

import com.bhukkad.config.ApiPaths;
import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.UnifiedSearchResponse;
import com.bhukkad.ratelimit.RateLimited;
import com.bhukkad.service.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ApiPaths.V1_PREFIX + "/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    @GetMapping
    @RateLimited("search")
    public ResponseEntity<ApiResponse<UnifiedSearchResponse>> unifiedSearch(@RequestParam String keyword) {
        return ResponseEntity.ok(ApiResponse.success(searchService.unifiedSearch(keyword)));
    }
}
