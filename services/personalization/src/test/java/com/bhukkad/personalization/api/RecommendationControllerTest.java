package com.bhukkad.personalization.api;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.personalization.dto.FeedRankResponse;
import com.bhukkad.personalization.dto.RecommendationResponse;
import com.bhukkad.personalization.service.RecommendationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationControllerTest {

    @Mock
    private RecommendationService recommendationService;

    private RecommendationController controller;

    @BeforeEach
    void setUp() {
        controller = new RecommendationController(recommendationService);
    }

    @Test
    void reorderSuggestions_shouldReturnOkWithRecommendations() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        when(recommendationService.reorderSuggestions(1L)).thenReturn(Arrays.asList(
                RecommendationResponse.builder().itemId(100L).build()
        ));

        ResponseEntity<List<RecommendationResponse>> response = controller.reorderSuggestions(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).getItemId()).isEqualTo(100L);
    }

    @Test
    void reorderSuggestions_shouldReturnEmptyListWhenNoRecommendations() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        when(recommendationService.reorderSuggestions(1L)).thenReturn(Collections.emptyList());

        ResponseEntity<List<RecommendationResponse>> response = controller.reorderSuggestions(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void collaborativeSuggestions_shouldReturnOkWithRecommendations() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        when(recommendationService.collaborativeSuggestions(1L)).thenReturn(Arrays.asList(
                RecommendationResponse.builder().itemId(200L).build()
        ));

        ResponseEntity<List<RecommendationResponse>> response = controller.collaborativeSuggestions(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void timeAwareSuggestions_shouldReturnOkWithRecommendations() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        when(recommendationService.timeAwareSuggestions(1L)).thenReturn(Arrays.asList(
                RecommendationResponse.builder().itemId(300L).build()
        ));

        ResponseEntity<List<RecommendationResponse>> response = controller.timeAwareSuggestions(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void rankFeed_shouldReturnOkWithRankedRestaurants() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        FeedRankResponse feedRank = new FeedRankResponse(
                Arrays.asList(10L, 20L, 30L),
                Map.of(10L, 10.0, 20L, 5.0, 30L, 0.0)
        );
        when(recommendationService.rankRestaurantsForCustomer(1L, Arrays.asList(10L, 20L, 30L)))
                .thenReturn(feedRank);

        ResponseEntity<FeedRankResponse> response = controller.rankFeed(principal, Arrays.asList(10L, 20L, 30L));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().getRankedRestaurantIds()).containsExactly(10L, 20L, 30L);
    }

    @Test
    void rankFeed_shouldReturnBadRequestWhenRestaurantIdsNull() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");

        ResponseEntity<FeedRankResponse> response = controller.rankFeed(principal, null);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void rankFeed_shouldReturnBadRequestWhenRestaurantIdsEmpty() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");

        ResponseEntity<FeedRankResponse> response = controller.rankFeed(principal, Collections.emptyList());

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void rankFeed_shouldReturnBadRequestWhenTooManyRestaurantIds() {
        TokenPrincipal principal = new TokenPrincipal(1L, "test@example.com", "CUSTOMER");
        List<Long> tooMany = Arrays.asList(new Long[101]);
        for (int i = 0; i < 101; i++) tooMany.set(i, (long) i);

        ResponseEntity<FeedRankResponse> response = controller.rankFeed(principal, tooMany);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
    }

    @Test
    void requireCustomerId_shouldThrowWhenPrincipalNull() {
        assertThatThrownBy(() -> controller.reorderSuggestions(null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Authenticated customer required");
    }

    @Test
    void requireCustomerId_shouldThrowWhenUserIdNull() {
        TokenPrincipal principal = new TokenPrincipal(null, "test@example.com", "CUSTOMER");

        assertThatThrownBy(() -> controller.reorderSuggestions(principal))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Authenticated customer required");
    }

    @Test
    void requireCustomerId_shouldExtractUserIdFromPrincipal() {
        TokenPrincipal principal = new TokenPrincipal(42L, "test@example.com", "CUSTOMER");
        when(recommendationService.reorderSuggestions(42L)).thenReturn(Collections.emptyList());

        ResponseEntity<List<RecommendationResponse>> response = controller.reorderSuggestions(principal);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}