package com.bhukkad.restaurant.api;

import com.bhukkad.restaurant.service.AutocompleteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Menu autocomplete (port of monolith {@code SearchController} autocomplete
 * surface).
 */
@RestController
@RequestMapping("/api/v1/search/autocomplete")
@RequiredArgsConstructor
public class AutocompleteController {

    private final AutocompleteService autocompleteService;

    @GetMapping
    public List<String> suggest(@RequestParam String q,
                                @RequestParam(defaultValue = "8") int limit) {
        // Clamp: a negative limit never terminated the trie walk (CPU DoS).
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return autocompleteService.suggest(q, safeLimit);
    }
}