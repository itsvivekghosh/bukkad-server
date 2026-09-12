package com.bhukkad.survey.domain.service.impl;

import com.bhukkad.common.error.BusinessException;
import com.bhukkad.common.error.DuplicateRequestException;
import com.bhukkad.common.error.UnauthorizedException;
import com.bhukkad.survey.api.dto.response.SurveyRatingsResponse;
import com.bhukkad.survey.domain.entity.DeliverySurvey;
import com.bhukkad.survey.domain.repository.DeliverySurveyRepository;
import com.bhukkad.survey.infrastructure.client.OrderOwnershipClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behaviour pins for the survey write/read rules: rating bounds (1–5,
 * nulls allowed), comment length, duplicate suppression per order, and the
 * average-rating projection (including the nested-row Hibernate quirk).
 */
@ExtendWith(MockitoExtension.class)
class SurveyServiceImplTest {

    @Mock private DeliverySurveyRepository surveyRepository;
    @Mock private OrderOwnershipClient ownershipClient;
    @InjectMocks private SurveyServiceImpl service;

    private void ownerAllowed() {
        when(ownershipClient.ownsOrder(any(), any())).thenReturn(true);
    }

    @Test
    void missingCustomerId_unauthorized() {
        assertThatThrownBy(() -> service.submitSurvey(
                null, 1L, 5L, 5, 5, 5, null))
                .isInstanceOf(UnauthorizedException.class);
        verify(ownershipClient, never()).ownsOrder(any(), any());
    }

    @Test
    void missingOrderId_businessException() {
        assertThatThrownBy(() -> service.submitSurvey(
                7L, null, 5L, 5, 5, 5, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Order id");
        verify(ownershipClient, never()).ownsOrder(any(), any());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 6, 99})
    void ratingOutOfRange_rejected(int bad) {
        ownerAllowed();
        assertThatThrownBy(() -> service.submitSurvey(7L, 1L, 5L, bad, 5, 5, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 1 and 5");
        verify(surveyRepository, never()).save(any());
    }

    @Test
    void boundaryRatings_oneAndFiveAccepted() {
        ownerAllowed();
        when(surveyRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(surveyRepository.save(any(DeliverySurvey.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DeliverySurvey saved = service.submitSurvey(7L, 1L, 5L, 1, 5, null, null);

        assertThat(saved.getRatingDelivery()).isEqualTo(1);
        assertThat(saved.getRatingSpeed()).isNull();
    }

    @Test
    void duplicateSurvey_deniedBeforeSave() {
        ownerAllowed();
        when(surveyRepository.findByOrderId(1L)).thenReturn(Optional.of(new DeliverySurvey()));

        assertThatThrownBy(() -> service.submitSurvey(7L, 1L, 5L, 5, 5, 5, null))
                .isInstanceOf(DuplicateRequestException.class);
        verify(surveyRepository, never()).save(any());
    }

    @Test
    void oversizedComment_rejected() {
        ownerAllowed();
        assertThatThrownBy(() -> service.submitSurvey(
                7L, 1L, 5L, 5, 5, 5, "x".repeat(1001)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most 1000");
    }

    @Test
    void exactlyMaxCommentLength_acceptedAndTrimmed() {
        ownerAllowed();
        when(surveyRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(surveyRepository.save(any(DeliverySurvey.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        String padded = "x".repeat(500) + "   ";
        DeliverySurvey saved = service.submitSurvey(7L, 1L, 5L, 5, 5, 5, padded);
        assertThat(saved.getComment()).hasSize(500);
        assertThat(saved.getSubmittedAt()).isNotNull();
    }

    @Test
    void ratings_nullRestaurantId_businessException() {
        assertThatThrownBy(() -> service.getRestaurantRatings(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void ratings_noSurveys_returnsZeroes() {
        when(surveyRepository.findRestaurantAverages(5L)).thenReturn(List.of());

        SurveyRatingsResponse response = service.getRestaurantRatings(5L);

        assertThat(response).isEqualTo(new SurveyRatingsResponse(5L, 0.0, 0.0, 0.0, 0L));
    }

    @Test
    void ratings_flatRow_roundsToTwoDecimals() {
        when(surveyRepository.findRestaurantAverages(5L)).thenReturn(List.<Object[]>of(new Object[]{4.4444, 4.0, 3.999, 7L}));

        SurveyRatingsResponse response = service.getRestaurantRatings(5L);

        assertThat(response.ratingDelivery()).isEqualTo(4.44);
        assertThat(response.ratingSpeed()).isEqualTo(4.0);
        assertThat(response.surveyCount()).isEqualTo(7L);
    }

    @Test
    void ratings_nestedAggregateRow_unwrapped() {
        // Hibernate 6 wraps a single aggregate row in an extra array: { Object[]{...} }
        when(surveyRepository.findRestaurantAverages(5L)).thenReturn(List.<Object[]>of(new Object[]{new Object[]{4.5, 4.25, null, 12L}}));

        SurveyRatingsResponse response = service.getRestaurantRatings(5L);

        assertThat(response.ratingDelivery()).isEqualTo(4.5);
        assertThat(response.ratingSpeed()).isEqualTo(0.0); // null column → 0.0
        assertThat(response.surveyCount()).isEqualTo(12L);
    }

    @Test
    void ratings_shortRowWithNulls_countDefaultsToZero() {
        when(surveyRepository.findRestaurantAverages(5L)).thenReturn(List.<Object[]>of(new Object[]{null}));

        SurveyRatingsResponse response = service.getRestaurantRatings(5L);

        assertThat(response.ratingDelivery()).isEqualTo(0.0);
        assertThat(response.surveyCount()).isZero();
    }

    @Test
    void ratings_countAsNumberCoerced() {
        when(surveyRepository.findRestaurantAverages(5L)).thenReturn(List.<Object[]>of(new Object[]{4.0, 4.0, 4.0, Integer.valueOf(3)}));

        assertThat(service.getRestaurantRatings(5L).surveyCount()).isEqualTo(3L);
    }

    @Test
    void ratings_repositoryFailurePropagates() {
        when(surveyRepository.findRestaurantAverages(anyLong()))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.getRestaurantRatings(5L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }
}
