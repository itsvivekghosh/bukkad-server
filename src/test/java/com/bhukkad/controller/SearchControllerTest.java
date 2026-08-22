package com.bhukkad.controller;

import com.bhukkad.dto.response.ApiResponse;
import com.bhukkad.dto.response.AutocompleteSuggestion;
import com.bhukkad.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.AutocompleteService;
import com.bhukkad.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;
    @Mock
    private AutocompleteService autocompleteService;

    @InjectMocks
    private SearchController controller;

    @Test void unifiedSearch_returnsResponse() {
        UnifiedSearchResponse expected = UnifiedSearchResponse.builder()
                .restaurantCount(1).menuItemCount(2).build();
        when(searchService.unifiedSearch("pizza")).thenReturn(expected);

        ResponseEntity<ApiResponse<UnifiedSearchResponse>> resp = controller.unifiedSearch("pizza");

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(expected, resp.getBody().getData());
        verify(searchService).unifiedSearch("pizza");
    }

    @Test void suggest_withLimit_delegates() {
        var suggestion = AutocompleteSuggestion.builder().text("Pizza Palace").type("RESTAURANT").build();
        when(autocompleteService.suggest("piz", 5)).thenReturn(List.of(suggestion));

        ResponseEntity<ApiResponse<List<AutocompleteSuggestion>>> resp = controller.suggest("piz", 5);

        assertEquals(1, resp.getBody().getData().size());
        assertEquals("Pizza Palace", resp.getBody().getData().get(0).getText());
    }

    @Test void suggest_withoutLimit_delegatesNull() {
        when(autocompleteService.suggest("pan", null)).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<AutocompleteSuggestion>>> resp = controller.suggest("pan", null);

        assertEquals(200, resp.getStatusCode().value());
        verify(autocompleteService).suggest("pan", null);
    }
}
