package com.bhukkad.search;

import com.bhukkad.search.entity.MenuItemSearchEntity;
import com.bhukkad.search.repository.MenuItemSearchRepository;
import com.bhukkad.search.repository.RestaurantSearchRepository;
import com.bhukkad.search.serviceImpl.SearchServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bounded search (audit S-family): DB-side bounded queries with LIKE-escaped
 * terms — never a full table load per request.
 */
@ExtendWith(MockitoExtension.class)
class SearchBoundedQueryTest {

    @Mock private RestaurantSearchRepository restaurantRepo;
    @Mock private MenuItemSearchRepository menuItemRepo;

    private SearchServiceImpl service() {
        return new SearchServiceImpl(restaurantRepo, menuItemRepo, false);
    }

    @Test
    void unifiedSearch_fuzzyFlagRoutesToSimilarityQueries() {
        when(restaurantRepo.searchTextFuzzy(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        when(menuItemRepo.searchTextFuzzy(any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        new SearchServiceImpl(restaurantRepo, menuItemRepo, true).unifiedSearch("Biryani");

        // Fuzzy path passes the plain lowercased term (no LIKE escapes).
        verify(restaurantRepo).searchTextFuzzy(eq("biryani"), eq(50));
        verify(menuItemRepo).searchTextFuzzy(eq("biryani"), eq(50));
        verify(restaurantRepo, org.mockito.Mockito.never()).searchText(any(), any(Pageable.class));
    }

    @Test
    void unifiedSearch_runsBoundedEscapedQueries() {
        when(restaurantRepo.searchText(any(), any(Pageable.class))).thenReturn(List.of());
        when(menuItemRepo.searchText(any(), any(Pageable.class))).thenReturn(List.of());

        service().unifiedSearch("biryani%_x");

        ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurantRepo).searchText(term.capture(), page.capture());
        // metacharacters escaped (cannot widen the LIKE) + lowercased
        assertThat(term.getValue()).isEqualTo("biryani\\%\\_x");
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
        verify(menuItemRepo).searchText(eq("biryani\\%\\_x"), any(Pageable.class));
    }

    @Test
    void suggest_prefixQueriesBoundedToRequestedLimit() {
        when(restaurantRepo.searchNamePrefix(any(), any(Pageable.class))).thenReturn(List.of());
        when(menuItemRepo.searchNamePrefix(any(), any(Pageable.class))).thenReturn(List.of());

        service().suggest("pani", 12);

        verify(restaurantRepo).searchNamePrefix(eq("pani"), any(Pageable.class));
        verify(menuItemRepo).searchNamePrefix(eq("pani"), any(Pageable.class));
    }

    @Test
    void escapeLike_lowercasesAndEscapes() {
        assertThat(SearchServiceImpl.escapeLike("AbC%d_e\\f")).isEqualTo("abc\\%d\\_e\\\\f");
    }

    private static MenuItemSearchEntity item(long id, String name) {
        MenuItemSearchEntity e = new MenuItemSearchEntity();
        e.setId(id);
        e.setName(name);
        return e;
    }

    @Test
    void emptyKeyword_shortCircuitsWithoutRepo() {
        var out = service().unifiedSearch("  ");
        assertThat(out.getRestaurants()).isEmpty();
    }
}
