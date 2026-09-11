package com.bhukkad.personalization.serviceImpl;

import com.bhukkad.personalization.config.RecommendationProperties;
import com.bhukkad.personalization.dto.FeedRankResponse;
import com.bhukkad.personalization.dto.RecommendationResponse;
import com.bhukkad.personalization.service.RecommendationQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceImplTest {

    @Mock
    private RecommendationQueryService queryService;

    @Mock
    private RecommendationProperties properties;

    private RecommendationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecommendationServiceImpl(queryService, properties);
    }

    @Test
    void reorderSuggestions_shouldReturnMappedRecommendations() {
        when(properties.getMaxItems()).thenReturn(10);
        List<Object[]> freqResult = Arrays.asList(
                new Object[]{100L, 5L},
                new Object[]{101L, 3L}
        );
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(10))).thenReturn(freqResult);

        List<RecommendationResponse> result = service.reorderSuggestions(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getItemId()).isEqualTo(100L);
        assertThat(result.get(1).getItemId()).isEqualTo(101L);
    }

    @Test
    void reorderSuggestions_shouldReturnEmptyListWhenNoFrequencies() {
        when(properties.getMaxItems()).thenReturn(10);
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(10))).thenReturn(Collections.emptyList());

        List<RecommendationResponse> result = service.reorderSuggestions(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void collaborativeSuggestions_shouldReturnCoOrderedItems() {
        when(properties.getCoOrderedItemLimit()).thenReturn(50);
        when(properties.getMaxItems()).thenReturn(10);
        List<Object[]> ownFreq = Collections.singletonList(new Object[]{100L, 5L});
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(50))).thenReturn(ownFreq);
        
        List<Object[]> coOrdered = Arrays.asList(
                new Object[]{200L, 10L},
                new Object[]{201L, 8L}
        );
        when(queryService.findCoOrderedItems(eq(Arrays.asList(100L)), eq(1L), eq(30))).thenReturn(coOrdered);

        List<RecommendationResponse> result = service.collaborativeSuggestions(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getItemId()).isEqualTo(200L);
        assertThat(result.get(1).getItemId()).isEqualTo(201L);
    }

    @Test
    void collaborativeSuggestions_shouldReturnEmptyWhenNoOwnItems() {
        when(properties.getCoOrderedItemLimit()).thenReturn(50);
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(50))).thenReturn(Collections.emptyList());

        List<RecommendationResponse> result = service.collaborativeSuggestions(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void collaborativeSuggestions_shouldExcludeAlreadyOrderedItems() {
        when(properties.getCoOrderedItemLimit()).thenReturn(50);
        when(properties.getMaxItems()).thenReturn(10);
        List<Object[]> ownFreq = Collections.singletonList(new Object[]{100L, 5L});
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(50))).thenReturn(ownFreq);
        
        List<Object[]> coOrdered = Arrays.asList(
                new Object[]{100L, 10L},
                new Object[]{200L, 8L}
        );
        when(queryService.findCoOrderedItems(eq(Arrays.asList(100L)), eq(1L), eq(30))).thenReturn(coOrdered);

        List<RecommendationResponse> result = service.collaborativeSuggestions(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getItemId()).isEqualTo(200L);
    }

    @Test
    void collaborativeSuggestions_shouldRespectMaxItemsLimit() {
        when(properties.getCoOrderedItemLimit()).thenReturn(50);
        when(properties.getMaxItems()).thenReturn(2);
        List<Object[]> ownFreq = Collections.singletonList(new Object[]{100L, 5L});
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(50))).thenReturn(ownFreq);
        
        List<Object[]> coOrdered = Arrays.asList(
                new Object[]{200L, 10L},
                new Object[]{201L, 8L},
                new Object[]{202L, 5L}
        );
        when(queryService.findCoOrderedItems(eq(Arrays.asList(100L)), eq(1L), eq(6))).thenReturn(coOrdered);

        List<RecommendationResponse> result = service.collaborativeSuggestions(1L);

        assertThat(result).hasSize(2);
    }

    @Test
    void timeAwareSuggestions_shouldReturnReorderSuggestions() {
        when(properties.getMaxItems()).thenReturn(10);
        List<Object[]> freqResult = Collections.singletonList(new Object[]{100L, 5L});
        when(queryService.findCustomerItemFrequencies(eq(1L), eq(10))).thenReturn(freqResult);

        List<RecommendationResponse> result = service.timeAwareSuggestions(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getItemId()).isEqualTo(100L);
    }

    @Test
    void mealWindow_currentAt_shouldReturnBreakfastBefore11() {
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(8, 0)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.BREAKFAST);
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(10, 59)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.BREAKFAST);
    }

    @Test
    void mealWindow_currentAt_shouldReturnLunchBetween11And16() {
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(11, 0)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.LUNCH);
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(15, 59)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.LUNCH);
    }

    @Test
    void mealWindow_currentAt_shouldReturnSnacksBetween16And19() {
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(16, 0)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.SNACKS);
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(18, 59)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.SNACKS);
    }

    @Test
    void mealWindow_currentAt_shouldReturnDinnerAfter19() {
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(19, 0)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.DINNER);
        assertThat(RecommendationServiceImpl.MealWindow.currentAt(LocalTime.of(22, 0)))
                .isEqualTo(RecommendationServiceImpl.MealWindow.DINNER);
    }

    @Test
    void rankRestaurantsForCustomer_shouldRankByAffinityThenPosition() {
        when(properties.getCustomerAffinityLimit()).thenReturn(25);
        List<Object[]> affinities = Arrays.asList(
                new Object[]{10L, 10L},
                new Object[]{20L, 5L}
        );
        when(queryService.findCustomerRestaurantAffinities(eq(1L), eq(25))).thenReturn(affinities);

        FeedRankResponse result = service.rankRestaurantsForCustomer(1L, Arrays.asList(10L, 20L, 30L));

        assertThat(result.getRankedRestaurantIds()).containsExactly(10L, 20L, 30L);
        assertThat(result.getAffinityScores().get(10L)).isEqualTo(10.0);
        assertThat(result.getAffinityScores().get(20L)).isEqualTo(5.0);
        assertThat(result.getAffinityScores().get(30L)).isEqualTo(0.0);
    }

    @Test
    void rankRestaurantsForCustomer_shouldPreserveOrderForEqualScores() {
        when(properties.getCustomerAffinityLimit()).thenReturn(25);
        List<Object[]> affinities = Arrays.asList(
                new Object[]{10L, 5L},
                new Object[]{20L, 5L}
        );
        when(queryService.findCustomerRestaurantAffinities(eq(1L), eq(25))).thenReturn(affinities);

        FeedRankResponse result = service.rankRestaurantsForCustomer(1L, Arrays.asList(10L, 20L, 30L));

        assertThat(result.getRankedRestaurantIds()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void rankRestaurantsForCustomer_shouldHandleEmptyAffinities() {
        when(properties.getCustomerAffinityLimit()).thenReturn(25);
        when(queryService.findCustomerRestaurantAffinities(eq(1L), eq(25))).thenReturn(Collections.emptyList());

        FeedRankResponse result = service.rankRestaurantsForCustomer(1L, Arrays.asList(10L, 20L));

        assertThat(result.getRankedRestaurantIds()).containsExactly(10L, 20L);
        assertThat(result.getAffinityScores().get(10L)).isEqualTo(0.0);
        assertThat(result.getAffinityScores().get(20L)).isEqualTo(0.0);
    }

    @Test
    void rankRestaurantsForCustomer_shouldReturnScores() {
        when(properties.getCustomerAffinityLimit()).thenReturn(25);
        List<Object[]> affinities = Collections.singletonList(new Object[]{10L, 7L});
        when(queryService.findCustomerRestaurantAffinities(eq(1L), eq(25))).thenReturn(affinities);

        FeedRankResponse result = service.rankRestaurantsForCustomer(1L, Arrays.asList(10L));

        assertThat(result.getAffinityScores()).containsEntry(10L, 7.0);
    }
}