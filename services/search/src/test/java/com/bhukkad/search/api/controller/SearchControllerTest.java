package com.bhukkad.search.api.controller;

import com.bhukkad.search.api.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.api.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.config.SearchSyncProperties;
import com.bhukkad.search.domain.service.SearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HTTP boundary: unified search passthrough and the S-3 limit clamp — null
 * defaults to 8, negatives to 1, anything over 50 to 50 — so the service
 * never sees a bound its subList arithmetic cannot handle.
 */
@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock private SearchService searchService;

    private SearchController controller() {
        return new SearchController(searchService);
    }

    @Test
    void unifiedSearch_passthrough() {
        UnifiedSearchResponse response = new UnifiedSearchResponse(List.of(), List.of(), 0, 0);
        when(searchService.unifiedSearch("biryani")).thenReturn(response);

        var out = controller().unifiedSearch("biryani");

        assertThat(out.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(out.getBody()).isSameAs(response);
    }

    @Test
    void suggest_defaultLimitIs8() {
        when(searchService.suggest("bir", 8)).thenReturn(List.of());

        assertThat(controller().suggest("bir", null).getBody()).isEmpty();
    }

    @Test
    void suggest_clampsLowHighAndPassesThrough() {
        when(searchService.suggest(anyString(), anyInt())).thenReturn(List.of(
                new AutocompleteSuggestion("x", AutocompleteSuggestion.TYPE_RESTAURANT)));

        controller().suggest("q", -5);
        controller().suggest("q", 999);
        controller().suggest("q", 8);

        ArgumentCaptor<Integer> limits = ArgumentCaptor.forClass(Integer.class);
        verify(searchService, times(3)).suggest(eq("q"), limits.capture());
        assertThat(limits.getAllValues()).containsExactly(1, 50, 8);
    }

    @Test
    void syncProperties_defaultsAndAccessors() {
        SearchSyncProperties properties = new SearchSyncProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getIntervalMs()).isEqualTo(300_000L);
        assertThat(properties.getRestaurantsPerCycle()).isEqualTo(20);

        properties.setEnabled(false);
        properties.setIntervalMs(1000L);
        properties.setRestaurantsPerCycle(5);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getIntervalMs()).isEqualTo(1000L);
        assertThat(properties.getRestaurantsPerCycle()).isEqualTo(5);
    }
}
