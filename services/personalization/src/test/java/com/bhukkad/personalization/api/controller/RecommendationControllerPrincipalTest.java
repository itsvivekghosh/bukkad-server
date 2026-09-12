package com.bhukkad.personalization.api.controller;

import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.common.security.TokenPrincipal;
import com.bhukkad.personalization.api.controller.RecommendationController;
import com.bhukkad.personalization.domain.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1-AUTH deliverable 6 (IDOR): the recommendation surface derives the
 * customer from the authenticated principal — the previous spoofable
 * {@code X-Customer-Id} header contract is gone, so cross-customer reads are
 * impossible by construction.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationControllerPrincipalTest {

    @Mock
    private RecommendationService recommendationService;

    @InjectMocks
    private RecommendationController controller;

    @Test
    void suggestions_usePrincipalUserId_neverAClientHeader() {
        when(recommendationService.reorderSuggestions(eq(42L))).thenReturn(List.of());

        assertThat(controller.reorderSuggestions(new TokenPrincipal(42L, "a@b.com", "CUSTOMER")))
                .isNotNull();
        // 42 — the JWT subject — is the ONLY id the service ever sees.
        verify(recommendationService).reorderSuggestions(eq(42L));
    }

    @Test
    void feedRank_usesPrincipalUserId() {
        when(recommendationService.rankRestaurantsForCustomer(eq(42L), anyList())).thenReturn(null);

        controller.rankFeed(new TokenPrincipal(42L, "a@b.com", "CUSTOMER"), List.of(1L, 2L));

        verify(recommendationService).rankRestaurantsForCustomer(eq(42L), eq(List.of(1L, 2L)));
    }

    @Test
    void unauthenticatedPrincipal_rejectedUnauthorized() {
        assertThatThrownBy(() -> controller.reorderSuggestions(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.collaborativeSuggestions(null))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> controller.timeAwareSuggestions(null))
                .isInstanceOf(UnauthorizedException.class);
    }
}
