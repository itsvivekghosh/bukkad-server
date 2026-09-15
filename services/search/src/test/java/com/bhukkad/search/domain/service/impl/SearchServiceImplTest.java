package com.bhukkad.search.domain.service.impl;

import com.bhukkad.common.cache.RedisCacheService;
import com.bhukkad.search.api.dto.response.AutocompleteSuggestion;
import com.bhukkad.search.api.dto.response.MenuItemSearchResult;
import com.bhukkad.search.api.dto.response.RestaurantSearchResult;
import com.bhukkad.search.api.dto.response.UnifiedSearchResponse;
import com.bhukkad.search.config.SearchFuzzyProperties;
import com.bhukkad.search.domain.entity.MenuItemSearchEntity;
import com.bhukkad.search.domain.entity.RestaurantSearchEntity;
import com.bhukkad.search.domain.repository.MenuItemSearchRepository;
import com.bhukkad.search.domain.repository.RestaurantSearchRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Query-path selection (escaped LIKE vs fuzzy trigrams), the 50-row unified
 * page bound, LIKE-metacharacter escaping (injection guard) and the
 * autocomplete limit interplay — all without a database.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock private RestaurantSearchRepository restaurants;
    @Mock private MenuItemSearchRepository menuItems;
    @Mock private ObjectProvider<RedisCacheService> cacheProvider;

    private SearchFuzzyProperties props(boolean enabled, double threshold) {
        SearchFuzzyProperties p = new SearchFuzzyProperties();
        p.setEnabled(enabled);
        p.setSimilarityThreshold(threshold);
        return p;
    }

    private SearchServiceImpl service(boolean fuzzy) {
        return new SearchServiceImpl(restaurants, menuItems, props(fuzzy, 0.35), cacheProvider);
    }

    private RestaurantSearchEntity restaurantEntity() {
        RestaurantSearchEntity e = new RestaurantSearchEntity();
        e.setId(1L);
        e.setName("Spice Route");
        e.setDescription("North Indian");
        e.setImageUrl("http://img/r1");
        e.setIsOpen(true);
        e.setIsActive(true);
        e.setAverageRating(4.5);
        e.setTotalReviews(200);
        e.setCuisineSummary("Indian");
        e.setDistanceKm(1.2);
        return e;
    }

    private MenuItemSearchEntity menuItemEntity() {
        MenuItemSearchEntity e = new MenuItemSearchEntity();
        e.setId(11L);
        e.setRestaurantId(1L);
        e.setName("Butter Chicken");
        e.setDescription("Creamy");
        e.setCategoryName("Mains");
        e.setPrice(320.0);
        e.setOriginalPrice(400.0);
        e.setDiscountPercentage(20.0);
        e.setAvailable(true);
        e.setFoodType("VEG");
        e.setIsVeg(true);
        e.setImageUrl("http://img/i11");
        e.setPreparationTime(25);
        e.setBestseller(true);
        e.setRestaurantName("Spice Route");
        e.setRestaurantDistanceKm(2.0);
        return e;
    }

    @Test
    void unifiedSearch_blankKeyword_shortCircuitsDb() {
        SearchServiceImpl service = service(false);

        assertThat(service.unifiedSearch("").getRestaurants()).isEmpty();
        assertThat(service.unifiedSearch(null).getMenuItems()).isEmpty();
        verify(restaurants, never()).searchText(anyString(), any());
        verify(menuItems, never()).searchText(anyString(), any());
    }

    @Test
    void unifiedSearch_likePath_escapesMetacharsAndPages50() {
        SearchServiceImpl service = service(false);
        when(restaurants.searchText(anyString(), any())).thenReturn(List.of(restaurantEntity()));
        when(menuItems.searchText(anyString(), any())).thenReturn(List.of(menuItemEntity()));

        UnifiedSearchResponse response = service.unifiedSearch("  50% off _best_\\new ");

        ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurants).searchText(term.capture(), page.capture());
        assertThat(term.getValue()).isEqualTo("50\\% off \\_best\\_\\\\new");
        assertThat(page.getValue().getPageSize()).isEqualTo(50);
        verify(menuItems).searchText(eq("50\\% off \\_best\\_\\\\new"), any(Pageable.class));

        assertThat(response.getRestaurantCount()).isEqualTo(1);
        assertThat(response.getMenuItemCount()).isEqualTo(1);
        RestaurantSearchResult r = response.getRestaurants().get(0);
        assertThat(r.getName()).isEqualTo("Spice Route");
        assertThat(r.getDistanceKm()).isEqualTo(1.2);
        assertThat(r.getCuisineSummary()).isEqualTo("Indian");
        MenuItemSearchResult m = response.getMenuItems().get(0);
        assertThat(m.getRestaurantDistanceKm()).isEqualTo(2.0);
        assertThat(m.getPreparationTime()).isEqualTo(25);
        assertThat(m.getFoodType()).isEqualTo("VEG");
    }

    @Test
    void unifiedSearch_fuzzyPath_sendsRawLowercasedTermAndThreshold() {
        SearchServiceImpl service = service(true);
        when(restaurants.searchTextFuzzy(anyString(), anyDouble(), any())).thenReturn(List.of());
        when(menuItems.searchTextFuzzy(anyString(), anyDouble(), any())).thenReturn(List.of());

        service.unifiedSearch(" ButTer ChickEN ");

        ArgumentCaptor<String> term = ArgumentCaptor.forClass(String.class);
        verify(restaurants).searchTextFuzzy(term.capture(), eq(0.35), any(Pageable.class));
        assertThat(term.getValue()).isEqualTo("butter chicken");
        verify(menuItems).searchTextFuzzy(eq("butter chicken"), eq(0.35), any(Pageable.class));
        verify(restaurants, never()).searchText(anyString(), any());
    }

    @Test
    void converters_carryEveryColumn() {
        SearchServiceImpl service = service(false);
        when(restaurants.searchText(anyString(), any())).thenReturn(List.of(restaurantEntity()));
        when(menuItems.searchText(anyString(), any())).thenReturn(List.of(menuItemEntity()));

        UnifiedSearchResponse r = service.unifiedSearch("x");
        RestaurantSearchResult res = r.getRestaurants().get(0);
        assertThat(res.getId()).isEqualTo(1L);
        assertThat(res.getDescription()).isEqualTo("North Indian");
        assertThat(res.getImageUrl()).isEqualTo("http://img/r1");
        assertThat(res.getIsOpen()).isTrue();
        assertThat(res.getIsActive()).isTrue();
        assertThat(res.getAverageRating()).isEqualTo(4.5);
        assertThat(res.getTotalReviews()).isEqualTo(200);

        MenuItemSearchResult m = r.getMenuItems().get(0);
        assertThat(m.getId()).isEqualTo(11L);
        assertThat(m.getName()).isEqualTo("Butter Chicken");
        assertThat(m.getDescription()).isEqualTo("Creamy");
        assertThat(m.getCategoryName()).isEqualTo("Mains");
        assertThat(m.getPrice()).isEqualTo(320.0);
        assertThat(m.getOriginalPrice()).isEqualTo(400.0);
        assertThat(m.getDiscountPercentage()).isEqualTo(20.0);
        assertThat(m.getAvailable()).isTrue();
        assertThat(m.getIsVeg()).isTrue();
        assertThat(m.getImageUrl()).isEqualTo("http://img/i11");
        assertThat(m.getBestseller()).isTrue();
        assertThat(m.getRestaurantName()).isEqualTo("Spice Route");
    }

    @Test
    void suggest_blankPrefix_returnsEmpty() {
        SearchServiceImpl service = service(false);

        assertThat(service.suggest("", 5)).isEmpty();
        assertThat(service.suggest(null, 5)).isEmpty();
    }

    @Test
    void suggest_menuQuerySkipped_whenRestaurantsFillLimit() {
        SearchServiceImpl service = service(false);
        RestaurantSearchEntity r1 = restaurantEntity();
        RestaurantSearchEntity r2 = restaurantEntity();
        when(restaurants.searchNamePrefix(eq("spi"), any(Pageable.class))).thenReturn(List.of(r1, r2));

        List<AutocompleteSuggestion> out = service.suggest(" SPI ", 2);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getType()).isEqualTo(AutocompleteSuggestion.TYPE_RESTAURANT);
        verify(menuItems, never()).searchNamePrefix(anyString(), any());
    }

    @Test
    void suggest_remainingQuotaGoesToMenuItems() {
        SearchServiceImpl service = service(false);
        when(restaurants.searchNamePrefix(anyString(), any())).thenReturn(List.of(restaurantEntity()));
        when(menuItems.searchNamePrefix(anyString(), any())).thenReturn(List.of(menuItemEntity()));

        List<AutocompleteSuggestion> out = service.suggest("butter", 4);

        assertThat(out).extracting(AutocompleteSuggestion::getType)
                .containsExactly(AutocompleteSuggestion.TYPE_RESTAURANT,
                        AutocompleteSuggestion.TYPE_MENU_ITEM);
        ArgumentCaptor<Pageable> menuPage = ArgumentCaptor.forClass(Pageable.class);
        verify(menuItems).searchNamePrefix(eq("butter"), menuPage.capture());
        assertThat(menuPage.getValue().getPageSize()).isEqualTo(3);
    }

    @Test
    void suggest_truncatesCombinedListToLimit() {
        SearchServiceImpl service = service(false);
        // 2 restaurants + 2 menu items for limit 3 → truncated to 3
        when(restaurants.searchNamePrefix(anyString(), any()))
                .thenReturn(List.of(restaurantEntity(), restaurantEntity()));
        when(menuItems.searchNamePrefix(anyString(), any()))
                .thenReturn(List.of(menuItemEntity(), menuItemEntity()));

        assertThat(service.suggest("x", 3)).hasSize(3);
    }

    @Test
    void suggest_padsNonPositiveLimitTo1() {
        SearchServiceImpl service = service(false);
        when(restaurants.searchNamePrefix(anyString(), any())).thenReturn(List.of());
        when(menuItems.searchNamePrefix(anyString(), any())).thenReturn(List.of());

        assertThat(service.suggest("x", 0)).isEmpty();

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(restaurants).searchNamePrefix(eq("x"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void escapeLike_lowercasesAndEscapes() {
        assertThat(SearchServiceImpl.escapeLike("A%_B\\C")).isEqualTo("a\\%\\_b\\\\c");
        assertThat(SearchServiceImpl.escapeLike("plain")).isEqualTo("plain");
    }
}
